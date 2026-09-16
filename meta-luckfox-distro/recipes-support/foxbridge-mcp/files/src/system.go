package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"

	"golang.org/x/sys/unix"
)

func registerSystemTools(s *server) {
	s.register(toolDef{
		Name:        "system_info",
		Description: "Return basic system metrics: uptime, load averages, memory usage (from /proc/meminfo), CPU temperature (from thermal_zone0), kernel release, and machine architecture. Read-only.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{},"additionalProperties":false}`),
		handler:     systemInfoTool,
	})
}

type sysInfo struct {
	UptimeSeconds float64           `json:"uptime_seconds"`
	LoadAvg       [3]float64        `json:"load_avg"`
	Memory        map[string]uint64 `json:"memory_kb"`
	CPUTempC      float64           `json:"cpu_temp_c,omitempty"`
	Kernel        string            `json:"kernel"`
	Machine       string            `json:"machine"`
	Hostname      string            `json:"hostname"`
}

func systemInfoTool(_ requestContext, _ json.RawMessage) (toolResult, error) {
	info := sysInfo{Memory: map[string]uint64{}}

	if up, err := readUptime(); err == nil {
		info.UptimeSeconds = up
	}
	if load, err := readLoadAvg(); err == nil {
		info.LoadAvg = load
	}
	if mem, err := readMeminfo(); err == nil {
		info.Memory = mem
	}
	if t, err := readCPUTemp(); err == nil {
		info.CPUTempC = t
	}
	if u, err := uname(); err == nil {
		info.Kernel = u.release
		info.Machine = u.machine
	}
	if h, err := os.Hostname(); err == nil {
		info.Hostname = h
	}
	return jsonResult(info)
}

func readUptime() (float64, error) {
	b, err := os.ReadFile("/proc/uptime")
	if err != nil {
		return 0, err
	}
	f := strings.Fields(string(b))
	if len(f) == 0 {
		return 0, fmt.Errorf("empty /proc/uptime")
	}
	return strconv.ParseFloat(f[0], 64)
}

func readLoadAvg() ([3]float64, error) {
	var out [3]float64
	b, err := os.ReadFile("/proc/loadavg")
	if err != nil {
		return out, err
	}
	f := strings.Fields(string(b))
	if len(f) < 3 {
		return out, fmt.Errorf("malformed /proc/loadavg")
	}
	for i := 0; i < 3; i++ {
		v, err := strconv.ParseFloat(f[i], 64)
		if err != nil {
			return out, err
		}
		out[i] = v
	}
	return out, nil
}

func readMeminfo() (map[string]uint64, error) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return nil, err
	}
	defer f.Close()
	wanted := map[string]bool{"MemTotal": true, "MemFree": true, "MemAvailable": true, "Buffers": true, "Cached": true}
	out := map[string]uint64{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := sc.Text()
		colon := strings.IndexByte(line, ':')
		if colon < 0 {
			continue
		}
		key := line[:colon]
		if !wanted[key] {
			continue
		}
		fields := strings.Fields(line[colon+1:])
		if len(fields) == 0 {
			continue
		}
		v, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			continue
		}
		out[key] = v
	}
	return out, nil
}

func readCPUTemp() (float64, error) {
	b, err := os.ReadFile("/sys/class/thermal/thermal_zone0/temp")
	if err != nil {
		return 0, err
	}
	// thermal_zoneN/temp is in millidegrees C.
	raw, err := strconv.Atoi(strings.TrimSpace(string(b)))
	if err != nil {
		return 0, err
	}
	return float64(raw) / 1000.0, nil
}

type unameInfo struct {
	release string
	machine string
}

// Use golang.org/x/sys/unix instead of stdlib syscall — x/sys gives
// Utsname fields a consistent [65]byte type across all GOARCHes, whereas
// syscall.Utsname varies between int8 and uint8 depending on arch.
func uname() (unameInfo, error) {
	var u unix.Utsname
	if err := unix.Uname(&u); err != nil {
		return unameInfo{}, err
	}
	return unameInfo{
		release: cstr(u.Release[:]),
		machine: cstr(u.Machine[:]),
	}, nil
}

func cstr(b []byte) string {
	if i := strings.IndexByte(string(b), 0); i >= 0 {
		return string(b[:i])
	}
	return string(b)
}
