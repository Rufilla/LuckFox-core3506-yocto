package main

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
)

func registerNetworkTools(s *server) {
	s.register(toolDef{
		Name:        "network_status",
		Description: "Return per-interface status (state, MAC, IPv4/IPv6 addresses) plus the default gateway. Reads from /sys/class/net and /proc/net/route, no external tools needed.",
		InputSchema: json.RawMessage(`{"type":"object","properties":{},"additionalProperties":false}`),
		handler:     networkStatusTool,
	})
}

type ifaceInfo struct {
	Name  string   `json:"name"`
	State string   `json:"state"`
	MAC   string   `json:"mac"`
	MTU   int      `json:"mtu"`
	Addrs []string `json:"addrs"`
}

type netStatus struct {
	Interfaces []ifaceInfo `json:"interfaces"`
	Default    string      `json:"default_route,omitempty"`
}

func networkStatusTool(_ requestContext, _ json.RawMessage) (toolResult, error) {
	ifs, err := net.Interfaces()
	if err != nil {
		return toolResult{}, fmt.Errorf("list interfaces: %w", err)
	}
	out := netStatus{}
	for _, iface := range ifs {
		if iface.Flags&net.FlagLoopback != 0 {
			continue
		}
		info := ifaceInfo{
			Name: iface.Name,
			MTU:  iface.MTU,
			MAC:  iface.HardwareAddr.String(),
		}
		// /sys/class/net/<name>/operstate is the authoritative link state.
		if b, err := os.ReadFile("/sys/class/net/" + iface.Name + "/operstate"); err == nil {
			info.State = strings.TrimSpace(string(b))
		} else if iface.Flags&net.FlagUp != 0 {
			info.State = "up"
		} else {
			info.State = "down"
		}
		addrs, _ := iface.Addrs()
		for _, a := range addrs {
			info.Addrs = append(info.Addrs, a.String())
		}
		sort.Strings(info.Addrs)
		out.Interfaces = append(out.Interfaces, info)
	}
	sort.Slice(out.Interfaces, func(i, j int) bool { return out.Interfaces[i].Name < out.Interfaces[j].Name })

	if gw, iface, err := readDefaultRoute(); err == nil && gw != "" {
		out.Default = gw + " via " + iface
	}
	return jsonResult(out)
}

// /proc/net/route format: header + lines of Iface Destination Gateway ...
// all in hex, little-endian. Default route = Destination 00000000.
func readDefaultRoute() (gw, iface string, err error) {
	b, err := os.ReadFile("/proc/net/route")
	if err != nil {
		return "", "", err
	}
	lines := strings.Split(string(b), "\n")
	for i, line := range lines {
		if i == 0 {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) < 3 {
			continue
		}
		if fields[1] != "00000000" {
			continue
		}
		gwHex := fields[2]
		if len(gwHex) != 8 {
			continue
		}
		ipBytes := make([]byte, 4)
		for j := 0; j < 4; j++ {
			v, err := strconv.ParseUint(gwHex[(3-j)*2:(3-j)*2+2], 16, 8)
			if err != nil {
				return "", "", err
			}
			ipBytes[j] = byte(v)
		}
		return net.IP(ipBytes).String(), fields[0], nil
	}
	return "", "", nil
}
