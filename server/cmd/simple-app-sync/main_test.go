package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"
)

func TestPairAndSynchronizeAcrossDevices(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := (&server{db: db, pairCode: "pair-code-with-enough-entropy"}).routes()

	tokenA := pairDevice(t, handler, "device-a", "Phone")
	tokenB := pairDevice(t, handler, "device-b", "Tablet")
	request := syncRequest{
		ProtocolVersion: protocolVersion,
		DeviceID:        "device-a",
		Operations: []operation{{
			OperationID: "operation-a", EntityType: "money", EntityID: "entry-a",
			Revision: "2026-09-23T00:00:00.000Z-0000", DeviceID: "device-a",
			Payload: json.RawMessage(`{"id":"entry-a","title":"Lunch"}`),
		}},
	}
	responseA := syncCall(t, handler, tokenA, request, http.StatusOK)
	if len(responseA.AcceptedOperationIDs) != 1 || len(responseA.Changes) != 1 || responseA.Cursor == 0 {
		t.Fatalf("unexpected first sync response: %+v", responseA)
	}

	responseB := syncCall(t, handler, tokenB, syncRequest{ProtocolVersion: protocolVersion, DeviceID: "device-b"}, http.StatusOK)
	if len(responseB.Changes) != 1 || responseB.Changes[0].EntityID != "entry-a" {
		t.Fatalf("second device did not receive the change: %+v", responseB)
	}
}

func TestRevokedDeviceCannotSynchronize(t *testing.T) {
	db, err := openDatabase(filepath.Join(t.TempDir(), "sync.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	handler := (&server{db: db, pairCode: "pair-code-with-enough-entropy"}).routes()
	token := pairDevice(t, handler, "device-a", "Phone")
	revoke := httptest.NewRequest(http.MethodPost, "/v1/devices/revoke", nil)
	revoke.Header.Set("Authorization", "Bearer "+token)
	revokeResponse := httptest.NewRecorder()
	handler.ServeHTTP(revokeResponse, revoke)
	if revokeResponse.Code != http.StatusNoContent {
		t.Fatalf("revoke returned %d: %s", revokeResponse.Code, revokeResponse.Body.String())
	}
	syncCall(t, handler, token, syncRequest{ProtocolVersion: protocolVersion, DeviceID: "device-a"}, http.StatusUnauthorized)
}

func pairDevice(t *testing.T, handler http.Handler, deviceID, name string) string {
	t.Helper()
	body, err := json.Marshal(pairRequest{PairCode: "pair-code-with-enough-entropy", DeviceID: deviceID, Name: name})
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/pair", bytes.NewReader(body))
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusCreated {
		t.Fatalf("pair returned %d: %s", response.Code, response.Body.String())
	}
	var paired pairResponse
	if err := json.NewDecoder(response.Body).Decode(&paired); err != nil {
		t.Fatal(err)
	}
	if len(paired.Token) != 64 {
		t.Fatalf("unexpected token: %q", paired.Token)
	}
	return paired.Token
}

func syncCall(t *testing.T, handler http.Handler, token string, input syncRequest, expectedStatus int) syncResponse {
	t.Helper()
	body, err := json.Marshal(input)
	if err != nil {
		t.Fatal(err)
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/sync", bytes.NewReader(body))
	request.Header.Set("Authorization", "Bearer "+token)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != expectedStatus {
		t.Fatalf("sync returned %d: %s", response.Code, response.Body.String())
	}
	var output syncResponse
	if expectedStatus == http.StatusOK {
		if err := json.NewDecoder(response.Body).Decode(&output); err != nil {
			t.Fatal(err)
		}
	}
	return output
}
