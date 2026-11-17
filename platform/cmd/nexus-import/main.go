// Command nexus-import 一次性把 service-nexus-shared 的 SQLite 数据与文件实体迁入 platformd。
//
// 迁移口径见 docs/plans/055.nexus-shared-merge-into-platform.md：
//   - 只复制数据，不重建 schema；目标库的 000013–000021 迁移必须先应用（本工具会自动迁移）；
//   - 幂等：INSERT OR IGNORE（主键/唯一键去重），可重复执行；
//   - 迁移前默认用 VACUUM INTO 备份目标库；建议先停止 platformd 写入流量；
//   - 文件实体按 stored_name 复制（Nexus 的 storage_path 就是 stored_name）。
package main

import (
	"context"
	"database/sql"
	"flag"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
)

// tables 按外键依赖顺序排列：父表在被引用表之前。
var tables = []string{
	"settings",
	"messages",
	"files",
	"interactions",
	"roles",
	"subject_roles",
	"departments",
	"subject_departments",
}

// appScopedTables 是 000021 之后带 app_code 的表及其历史数据归属。
// 旧 Nexus 库没有该列，导入时按产品回填。
var appScopedTables = map[string]string{
	"settings":     "aceso",
	"messages":     "aceso",
	"files":        "agora",
	"interactions": "aceso",
	"roles":        "aceso",
}

func main() {
	sourceDatabase := flag.String("source-db", "../service-nexus-shared/.data/nexus.sqlite", "旧 Nexus SQLite 路径（只读；模块删除后指向归档副本）")
	sourceFiles := flag.String("source-files", "../service-nexus-shared/.data/files", "旧 Nexus 文件实体目录（模块删除后指向归档副本）")
	targetDatabase := flag.String("target-db", ".data/platformd.sqlite", "platformd SQLite 路径")
	targetFiles := flag.String("target-files", ".data/files", "platformd 文件实体目录（PLATFORMD_FILES_DIR）")
	dryRun := flag.Bool("dry-run", false, "只报告将迁移的行数与文件数，不写入")
	overwrite := flag.Bool("overwrite", false, "覆盖目标目录中已存在的同名文件实体")
	skipBackup := flag.Bool("skip-backup", false, "不执行迁移前的 VACUUM INTO 备份")
	flag.Parse()

	if err := run(*sourceDatabase, *sourceFiles, *targetDatabase, *targetFiles, *dryRun, *overwrite, *skipBackup); err != nil {
		fmt.Fprintln(os.Stderr, "nexus-import:", err)
		os.Exit(1)
	}
}

func run(sourceDatabasePath, sourceFilesDir, targetDatabasePath, targetFilesDir string, dryRun, overwrite, skipBackup bool) error {
	if _, err := os.Stat(sourceDatabasePath); err != nil {
		return fmt.Errorf("source database is not readable: %w", err)
	}

	ctx := context.Background()
	if !dryRun && !skipBackup {
		if _, err := os.Stat(targetDatabasePath); err == nil {
			backupPath := targetDatabasePath + ".bak-" + time.Now().Format("20060102T150405")
			if err := backupDatabase(ctx, targetDatabasePath, backupPath); err != nil {
				return fmt.Errorf("backup target database: %w", err)
			}
			fmt.Printf("backup: %s\n", backupPath)
		}
	}

	databaseConnection, err := database.OpenSQLite(ctx, targetDatabasePath)
	if err != nil {
		return err
	}
	defer databaseConnection.Close()

	migrationResult, err := database.Migrate(ctx, databaseConnection, migrations.Files)
	if err != nil {
		return fmt.Errorf("migrate target database: %w", err)
	}
	if migrationResult.Applied > 0 {
		fmt.Printf("applied migrations: %d\n", migrationResult.Applied)
	}

	if _, err := databaseConnection.ExecContext(ctx, "ATTACH DATABASE ? AS nexus", sourceDatabasePath); err != nil {
		return fmt.Errorf("attach source database: %w", err)
	}
	defer func() { _, _ = databaseConnection.ExecContext(ctx, "DETACH DATABASE nexus") }()

	for _, table := range tables {
		if err := verifyColumns(ctx, databaseConnection, table); err != nil {
			return err
		}
	}

	if dryRun {
		for _, table := range tables {
			rows, err := missingRowCount(ctx, databaseConnection, table)
			if err != nil {
				return err
			}
			fmt.Printf("would insert %-22s %d rows\n", table, rows)
		}
		files, err := countFiles(sourceFilesDir)
		if err != nil {
			return err
		}
		fmt.Printf("would copy %d file entities from %s\n", files, sourceFilesDir)
		return nil
	}

	for _, table := range tables {
		statement, err := copyStatement(ctx, databaseConnection, table)
		if err != nil {
			return err
		}
		result, err := databaseConnection.ExecContext(ctx, statement)
		if err != nil {
			return fmt.Errorf("copy %s: %w", table, err)
		}
		inserted, _ := result.RowsAffected()
		var total int64
		if err := databaseConnection.QueryRowContext(ctx, "SELECT COUNT(*) FROM main."+table).Scan(&total); err != nil {
			return fmt.Errorf("count %s: %w", table, err)
		}
		fmt.Printf("copied %-22s inserted=%-5d total=%d\n", table, inserted, total)
	}

	copied, skipped, err := copyFiles(sourceFilesDir, targetFilesDir, overwrite)
	if err != nil {
		return fmt.Errorf("copy file entities: %w", err)
	}
	fmt.Printf("files: copied=%d skipped=%d dir=%s\n", copied, skipped, targetFilesDir)
	return nil
}

func backupDatabase(ctx context.Context, databasePath, backupPath string) error {
	databaseConnection, err := sql.Open("sqlite", databasePath)
	if err != nil {
		return err
	}
	defer databaseConnection.Close()
	if _, err := databaseConnection.ExecContext(ctx, "VACUUM INTO ?", backupPath); err != nil {
		return err
	}
	return nil
}

func verifyColumns(ctx context.Context, databaseConnection *sql.DB, table string) error {
	targetColumns, err := tableColumns(ctx, databaseConnection, "main", table)
	if err != nil {
		return err
	}
	sourceColumns, err := tableColumns(ctx, databaseConnection, "nexus", table)
	if err != nil {
		return err
	}
	expected := sourceColumns
	if _, scoped := appScopedTables[table]; scoped {
		expected = insertAppCodeColumn(sourceColumns)
	}
	if len(targetColumns) != len(expected) {
		return fmt.Errorf("%s column mismatch: target=%v source=%v", table, targetColumns, expected)
	}
	for index := range targetColumns {
		if targetColumns[index] != expected[index] {
			return fmt.Errorf("%s column mismatch: target=%v source=%v", table, targetColumns, expected)
		}
	}
	return nil
}

// insertAppCodeColumn 复刻 000021 的列顺序：app_code 紧跟 id。
func insertAppCodeColumn(columns []string) []string {
	expected := make([]string, 0, len(columns)+1)
	for _, column := range columns {
		expected = append(expected, column)
		if column == "id" {
			expected = append(expected, "app_code")
		}
	}
	return expected
}

// copyStatement 为带 app_code 的表显式列出列并回填产品归属；其余表保持 SELECT *。
func copyStatement(ctx context.Context, databaseConnection *sql.DB, table string) (string, error) {
	appCode, scoped := appScopedTables[table]
	if !scoped {
		return fmt.Sprintf("INSERT OR IGNORE INTO main.%s SELECT * FROM nexus.%s", table, table), nil
	}
	sourceColumns, err := tableColumns(ctx, databaseConnection, "nexus", table)
	if err != nil {
		return "", err
	}
	targetColumns := insertAppCodeColumn(sourceColumns)
	selectColumns := make([]string, 0, len(sourceColumns))
	for _, column := range sourceColumns {
		if column == "id" {
			selectColumns = append(selectColumns, "id", "'"+appCode+"'")
			continue
		}
		selectColumns = append(selectColumns, column)
	}
	return fmt.Sprintf("INSERT OR IGNORE INTO main.%s (%s) SELECT %s FROM nexus.%s",
		table, strings.Join(targetColumns, ", "), strings.Join(selectColumns, ", "), table), nil
}

func tableColumns(ctx context.Context, databaseConnection *sql.DB, schema string, table string) ([]string, error) {
	rows, err := databaseConnection.QueryContext(ctx, "PRAGMA "+schema+".table_info("+table+")")
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	columns := make([]string, 0, 8)
	for rows.Next() {
		var (
			position int
			name     string
			kind     string
			notNull  int
			fallback sql.NullString
			primary  int
		)
		if err := rows.Scan(&position, &name, &kind, &notNull, &fallback, &primary); err != nil {
			return nil, err
		}
		columns = append(columns, name)
	}
	return columns, rows.Err()
}

func missingRowCount(ctx context.Context, databaseConnection *sql.DB, table string) (int64, error) {
	var count int64
	query := fmt.Sprintf("SELECT COUNT(*) FROM nexus.%s WHERE id NOT IN (SELECT id FROM main.%s)", table, table)
	if err := databaseConnection.QueryRowContext(ctx, query).Scan(&count); err != nil {
		return 0, fmt.Errorf("count missing rows in %s: %w", table, err)
	}
	return count, nil
}

func countFiles(directory string) (int, error) {
	if _, err := os.Stat(directory); err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	count := 0
	err := filepath.WalkDir(directory, func(path string, entry os.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if !entry.IsDir() {
			count++
		}
		return nil
	})
	return count, err
}

func copyFiles(sourceDirectory, targetDirectory string, overwrite bool) (int, int, error) {
	if _, err := os.Stat(sourceDirectory); err != nil {
		if os.IsNotExist(err) {
			return 0, 0, nil
		}
		return 0, 0, err
	}
	if err := os.MkdirAll(targetDirectory, 0o750); err != nil {
		return 0, 0, err
	}

	copied, skipped := 0, 0
	err := filepath.WalkDir(sourceDirectory, func(path string, entry os.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		relative, err := filepath.Rel(sourceDirectory, path)
		if err != nil {
			return err
		}
		destination := filepath.Join(targetDirectory, relative)
		if err := os.MkdirAll(filepath.Dir(destination), 0o750); err != nil {
			return err
		}
		if _, err := os.Stat(destination); err == nil && !overwrite {
			skipped++
			return nil
		}
		source, err := os.Open(path)
		if err != nil {
			return err
		}
		defer source.Close()
		target, err := os.OpenFile(destination, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o640)
		if err != nil {
			return err
		}
		if _, err := io.Copy(target, source); err != nil {
			target.Close()
			return err
		}
		if err := target.Close(); err != nil {
			return err
		}
		copied++
		return nil
	})
	return copied, skipped, err
}
