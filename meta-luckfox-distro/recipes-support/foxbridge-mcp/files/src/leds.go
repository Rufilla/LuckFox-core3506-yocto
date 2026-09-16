package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// LED names are what the kernel exposes as /sys/class/leds/<name>. Valid
// names per the kernel LED class: [A-Za-z0-9:_-]+. We enforce this *before*
// touching the filesystem.
var ledNamePattern = regexp.MustCompile(`^[A-Za-z0-9:_-]+$`)

const ledsDir = "/sys/class/leds"

type ledInfo struct {
	Name         string `json:"name"`
	Brightness   int    `json:"brightness"`
	MaxBrightness int   `json:"max_brightness"`
	Trigger      string `json:"trigger"`
}

func registerLEDTools(s *server) {
	s.register(toolDef{
		Name:        "led_list",
		Description: "List all LEDs exposed by the kernel at /sys/class/leds with current brightness, max brightness, and active trigger.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{},"additionalProperties":false}`),
		handler:     ledListTool,
	})
	s.register(toolDef{
		Name:        "led_get",
		Description: "Read current brightness and trigger for a single LED by name.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{"name":{"type":"string","description":"LED name as it appears under /sys/class/leds"}},"required":["name"],"additionalProperties":false}`),
		handler:     ledGetTool,
	})
	s.register(toolDef{
		Name:        "led_set",
		Description: "Set LED brightness. Value must be an integer between 0 and the LED's max_brightness (typically 1 for GPIO LEDs, 255 for PWM LEDs).",
		InputSchema: json.RawMessage(`{"type":"object","properties":{"name":{"type":"string"},"value":{"type":"integer","minimum":0}},"required":["name","value"],"additionalProperties":false}`),
		handler:     ledSetTool,
	})
}

func ledListTool(_ requestContext, _ json.RawMessage) (toolResult, error) {
	entries, err := os.ReadDir(ledsDir)
	if err != nil {
		return toolResult{}, fmt.Errorf("read %s: %w", ledsDir, err)
	}
	out := make([]ledInfo, 0, len(entries))
	for _, e := range entries {
		info, err := readLED(e.Name())
		if err != nil {
			continue
		}
		out = append(out, info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return jsonResult(out)
}

func ledGetTool(_ requestContext, args json.RawMessage) (toolResult, error) {
	var a struct{ Name string `json:"name"` }
	if err := json.Unmarshal(args, &a); err != nil {
		return toolResult{}, fmt.Errorf("invalid args: %w", err)
	}
	if !ledNamePattern.MatchString(a.Name) {
		return toolResult{}, fmt.Errorf("invalid led name %q", a.Name)
	}
	info, err := readLED(a.Name)
	if err != nil {
		return toolResult{}, err
	}
	return jsonResult(info)
}

func ledSetTool(_ requestContext, args json.RawMessage) (toolResult, error) {
	var a struct {
		Name  string `json:"name"`
		Value int    `json:"value"`
	}
	if err := json.Unmarshal(args, &a); err != nil {
		return toolResult{}, fmt.Errorf("invalid args: %w", err)
	}
	if !ledNamePattern.MatchString(a.Name) {
		return toolResult{}, fmt.Errorf("invalid led name %q", a.Name)
	}
	if a.Value < 0 {
		return toolResult{}, fmt.Errorf("value must be >= 0")
	}

	maxB, err := readSysfsInt(filepath.Join(ledsDir, a.Name, "max_brightness"))
	if err != nil {
		return toolResult{}, fmt.Errorf("read max_brightness: %w", err)
	}
	if a.Value > maxB {
		return toolResult{}, fmt.Errorf("value %d exceeds max_brightness %d for led %s", a.Value, maxB, a.Name)
	}

	// sysfs write: plain open+write, NOT os.WriteFile (which on some
	// Go versions does a temp-file+rename that sysfs rejects).
	path := filepath.Join(ledsDir, a.Name, "brightness")
	f, err := os.OpenFile(path, os.O_WRONLY, 0)
	if err != nil {
		return toolResult{}, fmt.Errorf("open %s: %w", path, err)
	}
	defer f.Close()
	if _, err := f.WriteString(strconv.Itoa(a.Value)); err != nil {
		return toolResult{}, fmt.Errorf("write %s: %w", path, err)
	}
	return textResult("led %s set to %d", a.Name, a.Value), nil
}

func readLED(name string) (ledInfo, error) {
	if !ledNamePattern.MatchString(name) {
		return ledInfo{}, fmt.Errorf("invalid led name %q", name)
	}
	base := filepath.Join(ledsDir, name)
	brightness, err := readSysfsInt(filepath.Join(base, "brightness"))
	if err != nil {
		return ledInfo{}, err
	}
	maxBrightness, err := readSysfsInt(filepath.Join(base, "max_brightness"))
	if err != nil {
		return ledInfo{}, err
	}
	trig, _ := readActiveTrigger(filepath.Join(base, "trigger"))
	return ledInfo{
		Name:          name,
		Brightness:    brightness,
		MaxBrightness: maxBrightness,
		Trigger:       trig,
	}, nil
}

// /sys/class/leds/X/trigger looks like "none rc-feedback [heartbeat] timer".
// The bracketed token is the active trigger. Returns "" if none bracketed.
func readActiveTrigger(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	for _, tok := range strings.Fields(string(b)) {
		if strings.HasPrefix(tok, "[") && strings.HasSuffix(tok, "]") {
			return tok[1 : len(tok)-1], nil
		}
	}
	return "", nil
}

func readSysfsInt(path string) (int, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(b)))
}
