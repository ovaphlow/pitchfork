package logging

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"slices"
	"sync"
	"time"
)

func New(directory string, console io.Writer) (*slog.Logger, func() error, error) {
	jsonHandler, err := newDailyJSONHandler(directory)
	if err != nil {
		return nil, nil, err
	}

	textHandler := slog.NewTextHandler(console, &slog.HandlerOptions{Level: slog.LevelInfo})
	logger := slog.New(teeHandler{handlers: []slog.Handler{textHandler, jsonHandler}})
	return logger, jsonHandler.Close, nil
}

type teeHandler struct {
	handlers []slog.Handler
}

func (handler teeHandler) Enabled(context context.Context, level slog.Level) bool {
	for _, childHandler := range handler.handlers {
		if childHandler.Enabled(context, level) {
			return true
		}
	}
	return false
}

func (handler teeHandler) Handle(context context.Context, record slog.Record) error {
	for _, childHandler := range handler.handlers {
		if !childHandler.Enabled(context, record.Level) {
			continue
		}
		if err := childHandler.Handle(context, record); err != nil {
			return err
		}
	}
	return nil
}

func (handler teeHandler) WithAttrs(attributes []slog.Attr) slog.Handler {
	children := make([]slog.Handler, len(handler.handlers))
	for index, childHandler := range handler.handlers {
		children[index] = childHandler.WithAttrs(attributes)
	}
	return teeHandler{handlers: children}
}

func (handler teeHandler) WithGroup(name string) slog.Handler {
	children := make([]slog.Handler, len(handler.handlers))
	for index, childHandler := range handler.handlers {
		children[index] = childHandler.WithGroup(name)
	}
	return teeHandler{handlers: children}
}

type dailyJSONHandler struct {
	writer     *dailyJSONWriter
	attributes []slog.Attr
	groups     []string
}

func newDailyJSONHandler(directory string) (*dailyJSONHandler, error) {
	if err := os.MkdirAll(directory, 0o750); err != nil {
		return nil, fmt.Errorf("create log directory: %w", err)
	}
	return &dailyJSONHandler{writer: &dailyJSONWriter{directory: directory}}, nil
}

func (handler *dailyJSONHandler) Enabled(_ context.Context, level slog.Level) bool {
	return level >= slog.LevelWarn
}

func (handler *dailyJSONHandler) Handle(context context.Context, record slog.Record) error {
	return handler.writer.write(context, record, handler.attributes, handler.groups)
}

func (handler *dailyJSONHandler) WithAttrs(attributes []slog.Attr) slog.Handler {
	return &dailyJSONHandler{
		writer:     handler.writer,
		attributes: append(slices.Clone(handler.attributes), attributes...),
		groups:     slices.Clone(handler.groups),
	}
}

func (handler *dailyJSONHandler) WithGroup(name string) slog.Handler {
	return &dailyJSONHandler{
		writer:     handler.writer,
		attributes: slices.Clone(handler.attributes),
		groups:     append(slices.Clone(handler.groups), name),
	}
}

func (handler *dailyJSONHandler) Close() error {
	return handler.writer.close()
}

type dailyJSONWriter struct {
	directory string
	mutex     sync.Mutex
	date      string
	file      *os.File
}

func (writer *dailyJSONWriter) write(context context.Context, record slog.Record, attributes []slog.Attr, groups []string) error {
	writer.mutex.Lock()
	defer writer.mutex.Unlock()

	if err := writer.openFor(record.Time); err != nil {
		return err
	}

	handler := slog.Handler(slog.NewJSONHandler(writer.file, &slog.HandlerOptions{Level: slog.LevelWarn}))
	if len(attributes) > 0 {
		handler = handler.WithAttrs(attributes)
	}
	for _, group := range groups {
		handler = handler.WithGroup(group)
	}
	return handler.Handle(context, record)
}

func (writer *dailyJSONWriter) openFor(recordTime time.Time) error {
	date := recordTime.Local().Format("2006-01-02")
	if writer.file != nil && writer.date == date {
		return nil
	}
	if writer.file != nil {
		if err := writer.file.Close(); err != nil {
			return fmt.Errorf("close previous JSON log file: %w", err)
		}
		writer.file = nil
	}

	path := filepath.Join(writer.directory, "identityd-"+date+".jsonl")
	file, err := os.OpenFile(path, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o640)
	if err != nil {
		return fmt.Errorf("open JSON log file: %w", err)
	}
	writer.date = date
	writer.file = file
	return nil
}

func (writer *dailyJSONWriter) close() error {
	writer.mutex.Lock()
	defer writer.mutex.Unlock()

	if writer.file == nil {
		return nil
	}
	err := writer.file.Close()
	writer.file = nil
	return err
}
