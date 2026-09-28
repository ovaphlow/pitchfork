package identity_test

import (
	"fmt"
	"go/ast"
	"go/parser"
	"go/token"
	"io/fs"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"testing"
	"unicode"

	"github.com/ovaphlow/pitchfork/service-idp-go/db/migrations"
)

// 本文件维护两条枚举一致性守卫：
//
//  1. TestEnumConstantsMatchMigrations 双向校验 enums.go 的常量集合与
//     db/migrations 中 CHECK 约束的字面量集合完全一致。
//  2. TestNoEnumLiteralOutsideEnums 禁止生产代码（非测试文件）再出现枚举字面量。
//
// 测试文件自身刻意保留字面量：断言写死期望值才能独立验证常量的实际取值，
// 因此第二条守卫只扫描非测试文件。

var quotedSQLString = regexp.MustCompile(`'([^']*)'`)

// enumConstants 从 enums.go 源码解析出全部字符串常量，避免在测试里重复维护清单。
func enumConstants(t *testing.T) map[string]string {
	t.Helper()
	file, err := parser.ParseFile(token.NewFileSet(), "enums.go", nil, 0)
	if err != nil {
		t.Fatalf("parse enums.go: %v", err)
	}

	constants := make(map[string]string)
	for _, declaration := range file.Decls {
		genDecl, ok := declaration.(*ast.GenDecl)
		if !ok || genDecl.Tok != token.CONST {
			continue
		}
		for _, spec := range genDecl.Specs {
			valueSpec, ok := spec.(*ast.ValueSpec)
			if !ok {
				continue
			}
			for index, name := range valueSpec.Names {
				if index >= len(valueSpec.Values) {
					continue
				}
				literal, ok := valueSpec.Values[index].(*ast.BasicLit)
				if !ok || literal.Kind != token.STRING {
					continue
				}
				value, err := strconv.Unquote(literal.Value)
				if err != nil {
					t.Fatalf("unquote constant %s: %v", name.Name, err)
				}
				constants[name.Name] = value
			}
		}
	}
	if len(constants) == 0 {
		t.Fatal("enums.go 中未解析到任何字符串常量")
	}
	return constants
}

// schemaEnumValues 收集迁移文件中所有含汉字的 SQL 字面量。identity 域的迁移里
// 带汉字的字面量只用于 CHECK 约束，因此这个集合就是 schema 侧的枚举域。
func schemaEnumValues(t *testing.T) map[string]string {
	t.Helper()
	entries, err := fs.ReadDir(migrations.Files, ".")
	if err != nil {
		t.Fatalf("read embedded migrations: %v", err)
	}

	values := make(map[string]string)
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".sql") {
			continue
		}
		source, err := fs.ReadFile(migrations.Files, entry.Name())
		if err != nil {
			t.Fatalf("read migration %s: %v", entry.Name(), err)
		}
		for _, match := range quotedSQLString.FindAllStringSubmatch(string(source), -1) {
			if !containsHan(match[1]) {
				continue
			}
			values[match[1]] = entry.Name()
		}
	}
	if len(values) == 0 {
		t.Fatal("迁移文件中未解析到任何枚举字面量")
	}
	return values
}

func containsHan(value string) bool {
	for _, character := range value {
		if unicode.Is(unicode.Han, character) {
			return true
		}
	}
	return false
}

// 角色 code 由 EnsureBootstrap 播种而非迁移创建，因此不参与迁移一致性校验。
var roleCodeConstants = map[string]bool{
	"RoleCodeAdministrator": true,
	"RoleCodeAuditReader":   true,
}

func TestEnumConstantsMatchMigrations(t *testing.T) {
	constants := enumConstants(t)
	schemaValues := schemaEnumValues(t)

	byValue := make(map[string]string, len(constants))
	for name, value := range constants {
		byValue[value] = name
	}

	checked := 0
	for name, value := range constants {
		if roleCodeConstants[name] {
			continue
		}
		checked++
		if _, ok := schemaValues[value]; !ok {
			t.Errorf("常量 %s = %q 未出现在任何迁移的 CHECK 约束中", name, value)
		}
	}
	if checked == 0 {
		t.Fatal("没有校验到任何非角色常量")
	}

	missing := make([]string, 0)
	for value, file := range schemaValues {
		if _, ok := byValue[value]; !ok {
			missing = append(missing, fmt.Sprintf("%s 中的 %q", file, value))
		}
	}
	sort.Strings(missing)
	for _, entry := range missing {
		t.Errorf("迁移 %s 在 enums.go 中没有对应常量", entry)
	}
}

func TestNoEnumLiteralOutsideEnums(t *testing.T) {
	constants := enumConstants(t)
	byValue := make(map[string][]string, len(constants))
	for name, value := range constants {
		byValue[value] = append(byValue[value], name)
	}
	for value := range byValue {
		sort.Strings(byValue[value])
	}

	// 从 internal/identity 出发回到模块根目录。
	root := filepath.Join("..", "..")
	fileSet := token.NewFileSet()
	offenders := make([]string, 0)
	scanned := 0

	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			// 根目录自身名为 ".."，不能套用隐藏目录规则，否则整棵树会被跳过。
			if path == root {
				return nil
			}
			name := entry.Name()
			if strings.HasPrefix(name, ".") || name == "node_modules" || name == "bin" || name == "web" || name == "logs" {
				return fs.SkipDir
			}
			return nil
		}
		if !strings.HasSuffix(path, ".go") || strings.HasSuffix(path, "_test.go") {
			return nil
		}
		if entry.Name() == "enums.go" {
			return nil
		}

		file, parseErr := parser.ParseFile(fileSet, path, nil, 0)
		if parseErr != nil {
			return fmt.Errorf("parse %s: %w", path, parseErr)
		}
		scanned++
		ast.Inspect(file, func(node ast.Node) bool {
			literal, ok := node.(*ast.BasicLit)
			if !ok || literal.Kind != token.STRING {
				return true
			}
			value, unquoteErr := strconv.Unquote(literal.Value)
			if unquoteErr != nil {
				return true
			}
			if names, ok := byValue[value]; ok {
				// 同一字面量可能横跨多个枚举域（如 "凭据变更" 同属 revoked_reason 与
				// event_action）。此时必须按该参数对应的 SQL 列挑选常量，不能照搬首个候选，
				// 因此这里把所有候选都列出来。
				suggestion := "identity." + strings.Join(names, " 或 identity.")
				offenders = append(offenders, fmt.Sprintf("%s:%d 字面量 %q 应改用 %s",
					path, fileSet.Position(literal.Pos()).Line, value, suggestion))
			}
			return true
		})
		return nil
	})
	if err != nil {
		t.Fatalf("扫描生产代码失败: %v", err)
	}
	if scanned == 0 {
		t.Fatal("未扫描到任何生产代码文件，守卫失效")
	}

	sort.Strings(offenders)
	for _, offender := range offenders {
		t.Error(offender)
	}
}

// sharedEnumValues 记录有意跨域复用的枚举字面量：同一个值同时属于两个不同的
// CHECK 约束域。这类值在调用点极易误用另一个域的常量（两者取值相同，编译和
// 行为都不会报错），因此新增重复值必须在此显式登记。
var sharedEnumValues = map[string][]string{
	"凭据变更": {"AuditActionCredentialChanged", "RevokedReasonCredentialChanged"},
}

func TestEnumValuesSharedAcrossDomains(t *testing.T) {
	constants := enumConstants(t)
	byValue := make(map[string][]string, len(constants))
	for name, value := range constants {
		byValue[value] = append(byValue[value], name)
	}

	actual := make(map[string][]string)
	for value, names := range byValue {
		if len(names) > 1 {
			sort.Strings(names)
			actual[value] = names
		}
	}

	for value, names := range actual {
		expected, ok := sharedEnumValues[value]
		if !ok {
			t.Errorf("枚举值 %q 被多个常量复用（%s），请确认无误用风险后在 sharedEnumValues 中登记",
				value, strings.Join(names, ", "))
			continue
		}
		if strings.Join(expected, ",") != strings.Join(names, ",") {
			t.Errorf("枚举值 %q 的登记项与实际不符：登记 %v，实际 %v", value, expected, names)
		}
	}
	for value := range sharedEnumValues {
		if _, ok := actual[value]; !ok {
			t.Errorf("sharedEnumValues 登记的 %q 已不再被多个常量复用，应删除该登记", value)
		}
	}
}
