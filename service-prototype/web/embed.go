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

// Templates is the parsed template collection (layout + pages + fragment).
// Parsing happens once at package init; a parse failure panics, which is
// correct for embedded, version-controlled templates.
var Templates = template.Must(template.ParseFS(templateFiles, "templates/*.html"))

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
