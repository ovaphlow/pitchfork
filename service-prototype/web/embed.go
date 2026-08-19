// Package web embeds the server-rendered templates and the built static
// assets (htmx) of prototyped.
package web

import (
	"embed"
	"html/template"
	"io"
)

//go:embed all:static/*
var StaticFiles embed.FS

//go:embed templates/*
var templateFiles embed.FS

// Templates is the parsed template collection shared by the demo page
// and the fragment (layout + demo + fragment). Parsing happens once at
// package init; a parse failure panics, which is correct for embedded,
// version-controlled templates. The page files are listed explicitly
// instead of a templates/*.html glob: every page defines the layout's
// content/title hooks, and in one shared parse set the alphabetically
// last page would win for every page. The drill scenario management
// page therefore lives in its own template set (see scenarios.go).
var Templates = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/demo.html", "templates/fragment.html"))

// RenderDemo renders the demo page (layout + demo content) with the given
// greeting. All user-controlled input is HTML-escaped by html/template.
func RenderDemo(w io.Writer, greeting string) error {
	return Templates.ExecuteTemplate(w, "layout.html", map[string]string{"Greeting": greeting})
}

// RenderDemoFragment renders the htmx fragment resource. The message is
// template-escaped as well.
func RenderDemoFragment(w io.Writer, message string) error {
	return Templates.ExecuteTemplate(w, "fragment.html", map[string]string{"Message": message})
}
