package identity_test

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"testing"
)

// sqlKeyword 用来判断一个常量是不是 SQL 语句。
var sqlKeyword = regexp.MustCompile(`(?i)\b(SELECT|INSERT|UPDATE|DELETE)\b`)

// placeholder 匹配 sqlx 的 :name 命名参数。
var placeholder = regexp.MustCompile(`:([a-z_][a-z0-9_]*)`)

// sqlHelperQueryIndex 记录四个 helper 里「SQL 常量」参数的位置，
// 参数表固定在其后一位。按位置取值，才能把「传了变量」和「传了 nil」区分开。
//
//	namedGet(ctx, handle, dest, query, args)
//	namedSelect(ctx, handle, dest, query, args)
//	namedExec(ctx, handle, query, args)
//	namedExecRows(ctx, handle, query, args)
var sqlHelperQueryIndex = map[string]int{
	"namedGet":      3,
	"namedSelect":   3,
	"namedExec":     2,
	"namedExecRows": 2,
}

// callSite 描述一次 helper 调用。
type callSite struct {
	constName  string // 空表示不是业务调用点（sqlx.go 内部的管道调用）
	keys       map[string]bool
	verifiable bool
}

// sqlx.Named 只在运行期校验命名参数：SQL 里写了 :foo 而调用处没提供 foo，
// 要等语句真正执行才报错。现有测试覆盖了全部查询，但新加的查询未必有测试。
//
// 这个守卫把「参数名拼写错误 / 漏传」从运行期提前到测试期。它必须逐调用点
// 比较，而不是做包级并集比较：并集比较下，只要某个参数名在任意一个查询里被
// 提供过，就会满足所有用到该名字的查询，从而漏掉真实的漏传。
//
// 校验内容：
//  1. 每个调用点引用的 SQL 常量，其全部 :name 都必须由该调用点的 map 键提供；
//  2. 每个 SQL 常量都必须至少被一个可校验的调用点引用（防止静默跳过）；
//  3. 参数不是内联 map 字面量时明确失败，而不是当作「没有参数」放过；
//  4. 防呆阈值，避免守卫因解析失败而空转。
func TestSQLNamedParametersAreProvided(t *testing.T) {
	fileSet := token.NewFileSet()
	packages, err := parser.ParseDir(fileSet, ".", func(info os.FileInfo) bool {
		return !strings.HasSuffix(info.Name(), "_test.go")
	}, 0)
	if err != nil {
		t.Fatalf("解析 identity 包失败: %v", err)
	}

	// SQL 常量名 -> 它引用的命名参数集合
	sqlConstants := map[string]map[string]bool{}
	for _, parsed := range packages {
		for _, file := range parsed.Files {
			collectSQLConstants(file, sqlConstants)
		}
	}

	seen := map[string]int{}
	checkedCalls := 0

	for _, parsed := range packages {
		for _, file := range parsed.Files {
			ast.Inspect(file, func(node ast.Node) bool {
				call, ok := node.(*ast.CallExpr)
				if !ok {
					return true
				}
				callee, ok := call.Fun.(*ast.Ident)
				if !ok {
					return true
				}
				if _, isHelper := sqlHelperQueryIndex[callee.Name]; !isHelper {
					return true
				}

				site := inspectCall(call, callee.Name, sqlConstants)
				if site.constName == "" {
					// sqlx.go 内部的管道调用把查询作为变量传递，不属于业务调用点。
					return true
				}
				seen[site.constName]++
				if !site.verifiable {
					t.Errorf("%s 调用 %s 执行 SQL 常量 %s 时参数不是内联 map 字面量或 nil，无法静态校验命名参数",
						fileSet.Position(call.Pos()), callee.Name, site.constName)
					return true
				}

				checkedCalls++
				for name := range sqlConstants[site.constName] {
					if !site.keys[name] {
						t.Errorf("%s 调用 %s 执行 SQL 常量 %s 时漏传命名参数 :%s",
							fileSet.Position(call.Pos()), callee.Name, site.constName, name)
					}
				}
				return true
			})
		}
	}

	// 防呆：守卫必须真的扫到东西，否则会静默失效。
	if len(sqlConstants) < 25 {
		t.Fatalf("只识别出 %d 个 SQL 常量，守卫失效", len(sqlConstants))
	}
	if checkedCalls < 25 {
		t.Fatalf("只校验了 %d 个调用点，守卫失效", checkedCalls)
	}
	totalPlaceholders := 0
	for _, names := range sqlConstants {
		totalPlaceholders += len(names)
	}
	if totalPlaceholders < 40 {
		t.Fatalf("只识别出 %d 个命名参数，守卫失效", totalPlaceholders)
	}

	// 完整性：每个 SQL 常量都应被调用点引用，否则说明它被静默跳过了。
	// 这里用「见过」而不是「校验过」计数，避免与上面的无法校验报错重复。
	var unreferenced []string
	for name := range sqlConstants {
		if seen[name] == 0 {
			unreferenced = append(unreferenced, name)
		}
	}
	if len(unreferenced) > 0 {
		sort.Strings(unreferenced)
		t.Errorf("以下 SQL 常量没有被任何可校验的调用点引用（守卫可能静默跳过了它们）: %s",
			strings.Join(unreferenced, ", "))
	}

	t.Logf("已校验 %d 个调用点、%d 个 SQL 常量、%d 个命名参数",
		checkedCalls, len(sqlConstants), totalPlaceholders)
}

// collectSQLConstants 收集包内所有 SQL 常量及其命名参数。
func collectSQLConstants(file *ast.File, into map[string]map[string]bool) {
	for _, declaration := range file.Decls {
		generic, ok := declaration.(*ast.GenDecl)
		if !ok || generic.Tok != token.CONST {
			continue
		}
		for _, specification := range generic.Specs {
			value, ok := specification.(*ast.ValueSpec)
			if !ok {
				continue
			}
			for index, name := range value.Names {
				if index >= len(value.Values) {
					continue
				}
				literal, ok := value.Values[index].(*ast.BasicLit)
				if !ok || literal.Kind != token.STRING {
					continue
				}
				// 只认反引号原始字符串，避免把普通字符串误判成 SQL。
				if !strings.HasPrefix(literal.Value, "`") {
					continue
				}
				content, err := strconv.Unquote(literal.Value)
				if err != nil || !sqlKeyword.MatchString(content) {
					continue
				}
				names := map[string]bool{}
				for _, match := range placeholder.FindAllStringSubmatch(content, -1) {
					names[match[1]] = true
				}
				into[name.Name] = names
			}
		}
	}
}

// inspectCall 按参数位置解析一次 helper 调用。
func inspectCall(call *ast.CallExpr, helper string, sqlConstants map[string]map[string]bool) callSite {
	queryIndex := sqlHelperQueryIndex[helper]
	if len(call.Args) <= queryIndex+1 {
		return callSite{}
	}

	queryArgument, ok := call.Args[queryIndex].(*ast.Ident)
	if !ok {
		return callSite{}
	}
	if _, isSQL := sqlConstants[queryArgument.Name]; !isSQL {
		// 不是已知的 SQL 常量，说明这是 helper 内部的管道调用。
		return callSite{}
	}

	site := callSite{constName: queryArgument.Name, keys: map[string]bool{}, verifiable: true}
	arguments := call.Args[queryIndex+1]

	// 无参数查询按 nil 传参。
	if identifier, ok := arguments.(*ast.Ident); ok && identifier.Name == "nil" {
		return site
	}

	literal, ok := arguments.(*ast.CompositeLit)
	if !ok {
		site.verifiable = false
		return site
	}
	if _, isMap := literal.Type.(*ast.MapType); !isMap {
		site.verifiable = false
		return site
	}
	for _, element := range literal.Elts {
		pair, ok := element.(*ast.KeyValueExpr)
		if !ok {
			site.verifiable = false
			continue
		}
		key, ok := pair.Key.(*ast.BasicLit)
		if !ok || key.Kind != token.STRING {
			site.verifiable = false
			continue
		}
		if decoded, err := strconv.Unquote(key.Value); err == nil {
			site.keys[decoded] = true
		}
	}
	return site
}
