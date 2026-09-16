package main

import (
	"encoding/json"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"

	"github.com/warthog618/go-gpiocdev"
)

// /dev/gpiochipN where N is 0..9. Constrained regex because we feed this
// into filesystem paths.
var chipNamePattern = regexp.MustCompile(`^gpiochip[0-9]$`)

func registerGPIOTools(s *server) {
	s.register(toolDef{
		Name:        "gpio_list",
		Description: "List all GPIO chips exposed by the kernel at /dev/gpiochip*. Returns chip name, label, and number of lines.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{},"additionalProperties":false}`),
		handler:     gpioListTool,
	})
	s.register(toolDef{
		Name:        "gpio_read",
		Description: "Read the current value of a single GPIO line. The line is briefly requested as input, read, and released.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{"chip":{"type":"string","description":"Chip name like \"gpiochip0\""},"line":{"type":"integer","minimum":0,"maximum":255,"description":"Line offset within the chip"}},"required":["chip","line"],"additionalProperties":false}`),
		handler:     gpioReadTool,
	})
	s.register(toolDef{
		Name:        "gpio_write",
		Description: "Drive a single GPIO line to 0 or 1. The line is requested as output with the given initial value, held momentarily, then released. NOTE: because the line is released after the call, this is only suitable for level transitions that the consumer holds (e.g., toggling a latched signal) — for held outputs, use a kernel driver.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{"chip":{"type":"string"},"line":{"type":"integer","minimum":0,"maximum":255},"value":{"type":"integer","enum":[0,1]}},"required":["chip","line","value"],"additionalProperties":false}`),
		handler:     gpioWriteTool,
	})
}

type gpioChipInfo struct {
	Name     string `json:"name"`
	Label    string `json:"label"`
	NumLines int    `json:"num_lines"`
}

func gpioListTool(_ requestContext, _ json.RawMessage) (toolResult, error) {
	entries, err := os.ReadDir("/dev")
	if err != nil {
		return toolResult{}, fmt.Errorf("read /dev: %w", err)
	}
	var chips []gpioChipInfo
	for _, e := range entries {
		name := e.Name()
		if !strings.HasPrefix(name, "gpiochip") || !chipNamePattern.MatchString(name) {
			continue
		}
		c, err := gpiocdev.NewChip(name)
		if err != nil {
			continue
		}
		chips = append(chips, gpioChipInfo{
			Name:     c.Name,
			Label:    c.Label,
			NumLines: c.Lines(),
		})
		_ = c.Close()
	}
	sort.Slice(chips, func(i, j int) bool { return chips[i].Name < chips[j].Name })
	return jsonResult(chips)
}

func gpioReadTool(_ requestContext, args json.RawMessage) (toolResult, error) {
	var a struct {
		Chip string `json:"chip"`
		Line int    `json:"line"`
	}
	if err := json.Unmarshal(args, &a); err != nil {
		return toolResult{}, fmt.Errorf("invalid args: %w", err)
	}
	if !chipNamePattern.MatchString(a.Chip) {
		return toolResult{}, fmt.Errorf("invalid chip %q", a.Chip)
	}
	line, err := gpiocdev.RequestLine(a.Chip, a.Line, gpiocdev.AsInput, gpiocdev.WithConsumer("fb-mcp"))
	if err != nil {
		return toolResult{}, fmt.Errorf("request line: %w", err)
	}
	defer line.Close()
	v, err := line.Value()
	if err != nil {
		return toolResult{}, fmt.Errorf("read value: %w", err)
	}
	return jsonResult(map[string]any{"chip": a.Chip, "line": a.Line, "value": v})
}

func gpioWriteTool(_ requestContext, args json.RawMessage) (toolResult, error) {
	var a struct {
		Chip  string `json:"chip"`
		Line  int    `json:"line"`
		Value int    `json:"value"`
	}
	if err := json.Unmarshal(args, &a); err != nil {
		return toolResult{}, fmt.Errorf("invalid args: %w", err)
	}
	if !chipNamePattern.MatchString(a.Chip) {
		return toolResult{}, fmt.Errorf("invalid chip %q", a.Chip)
	}
	if a.Value != 0 && a.Value != 1 {
		return toolResult{}, fmt.Errorf("value must be 0 or 1")
	}
	line, err := gpiocdev.RequestLine(a.Chip, a.Line,
		gpiocdev.AsOutput(a.Value),
		gpiocdev.WithConsumer("fb-mcp"),
	)
	if err != nil {
		return toolResult{}, fmt.Errorf("request line as output: %w", err)
	}
	defer line.Close()
	return textResult("gpio %s line %d set to %d", a.Chip, a.Line, a.Value), nil
}
