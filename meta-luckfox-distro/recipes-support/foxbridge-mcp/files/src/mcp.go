package main

// Minimal MCP Streamable HTTP server (protocol version 2025-03-26).
//
// Why hand-rolled: the official github.com/modelcontextprotocol/go-sdk
// requires Go >= 1.23, but poky scarthgap ships Go 1.22.12. The MCP wire
// format is small enough that open-coding it is cleaner than backporting a
// toolchain.
//
// Supported methods: initialize, notifications/initialized, tools/list,
// tools/call, ping. All others respond with JSON-RPC error -32601.
//
// Transport: single POST /mcp endpoint. Responses are application/json only
// (no SSE streaming) — every tool implemented here is synchronous and fast.

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"sync"
)

const (
	protocolVersion = "2025-03-26"
	serverName      = "foxbridge-mcp"
	serverVersion   = "0.1.0"

	maxRequestBytes = 1 << 20 // 1 MiB — tool args are tiny
)

type jsonRPCRequest struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id,omitempty"`
	Method  string          `json:"method"`
	Params  json.RawMessage `json:"params,omitempty"`
}

type jsonRPCResponse struct {
	JSONRPC string          `json:"jsonrpc"`
	ID      json.RawMessage `json:"id"`
	Result  any             `json:"result,omitempty"`
	Error   *jsonRPCError   `json:"error,omitempty"`
}

type jsonRPCError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
	Data    any    `json:"data,omitempty"`
}

type toolHandler func(ctx requestContext, args json.RawMessage) (toolResult, error)

type toolDef struct {
	Name        string          `json:"name"`
	Description string          `json:"description"`
	InputSchema json.RawMessage `json:"inputSchema"`
	handler     toolHandler     `json:"-"`
}

type toolResult struct {
	Content []contentBlock `json:"content"`
	IsError bool           `json:"isError,omitempty"`
}

type contentBlock struct {
	Type string `json:"type"`
	Text string `json:"text,omitempty"`
}

type requestContext struct {
	r *http.Request
}

type server struct {
	mu    sync.RWMutex
	tools map[string]toolDef
	order []string
}

func newServer() *server {
	s := &server{tools: make(map[string]toolDef)}
	registerLEDTools(s)
	registerGPIOTools(s)
	registerSystemTools(s)
	registerNetworkTools(s)
	return s
}

func (s *server) register(t toolDef) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.tools[t.Name] = t
	s.order = append(s.order, t.Name)
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	// Reject non-local Origin if set. Browsers always set Origin on
	// cross-origin fetches; local CLI MCP clients typically don't set it.
	if origin := r.Header.Get("Origin"); origin != "" {
		if origin != "http://127.0.0.1:45521" && origin != "http://localhost:45521" {
			http.Error(w, "cross-origin rejected", http.StatusForbidden)
			return
		}
	}

	body, err := io.ReadAll(io.LimitReader(r.Body, maxRequestBytes))
	if err != nil {
		http.Error(w, "read body", http.StatusBadRequest)
		return
	}

	var req jsonRPCRequest
	if err := json.Unmarshal(body, &req); err != nil {
		writeJSONRPCError(w, nil, -32700, "parse error", nil)
		return
	}

	// Notifications (no id, JSON-RPC 2.0 server spec) get no response body.
	// In HTTP, we return 202 Accepted per the streamable HTTP spec.
	isNotification := len(req.ID) == 0

	resp, errResp := s.dispatch(requestContext{r: r}, req)

	if isNotification {
		w.WriteHeader(http.StatusAccepted)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if errResp != nil {
		_ = json.NewEncoder(w).Encode(jsonRPCResponse{JSONRPC: "2.0", ID: req.ID, Error: errResp})
		return
	}
	_ = json.NewEncoder(w).Encode(jsonRPCResponse{JSONRPC: "2.0", ID: req.ID, Result: resp})
}

func (s *server) dispatch(rc requestContext, req jsonRPCRequest) (any, *jsonRPCError) {
	switch req.Method {
	case "initialize":
		return map[string]any{
			"protocolVersion": protocolVersion,
			"capabilities": map[string]any{
				"tools": map[string]any{},
			},
			"serverInfo": map[string]any{
				"name":    serverName,
				"version": serverVersion,
			},
		}, nil

	case "notifications/initialized":
		return nil, nil

	case "ping":
		return map[string]any{}, nil

	case "tools/list":
		s.mu.RLock()
		defer s.mu.RUnlock()
		list := make([]toolDef, 0, len(s.order))
		for _, name := range s.order {
			list = append(list, s.tools[name])
		}
		return map[string]any{"tools": list}, nil

	case "tools/call":
		var params struct {
			Name      string          `json:"name"`
			Arguments json.RawMessage `json:"arguments"`
		}
		if err := json.Unmarshal(req.Params, &params); err != nil {
			return nil, &jsonRPCError{Code: -32602, Message: "invalid params: " + err.Error()}
		}
		s.mu.RLock()
		tool, ok := s.tools[params.Name]
		s.mu.RUnlock()
		if !ok {
			return nil, &jsonRPCError{Code: -32602, Message: fmt.Sprintf("unknown tool %q", params.Name)}
		}
		result, err := tool.handler(rc, params.Arguments)
		if err != nil {
			log.Printf("tool %s error: %v", params.Name, err)
			return toolResult{
				Content: []contentBlock{{Type: "text", Text: err.Error()}},
				IsError: true,
			}, nil
		}
		return result, nil

	default:
		return nil, &jsonRPCError{Code: -32601, Message: "method not found: " + req.Method}
	}
}

func writeJSONRPCError(w http.ResponseWriter, id json.RawMessage, code int, msg string, data any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(jsonRPCResponse{
		JSONRPC: "2.0", ID: id,
		Error: &jsonRPCError{Code: code, Message: msg, Data: data},
	})
}

func textResult(format string, args ...any) toolResult {
	return toolResult{Content: []contentBlock{{Type: "text", Text: fmt.Sprintf(format, args...)}}}
}

func jsonResult(v any) (toolResult, error) {
	b, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return toolResult{}, err
	}
	return toolResult{Content: []contentBlock{{Type: "text", Text: string(b)}}}, nil
}
