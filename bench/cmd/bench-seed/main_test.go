package main

import (
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/hex"
	"testing"
)

func TestEncryptEnvelopeLayoutMatchesAesGcmCipher(t *testing.T) {
	key, err := base64.StdEncoding.DecodeString("MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
	if err != nil || len(key) != 32 {
		t.Fatalf("invalid test key: %v", err)
	}
	plaintext := `{"apiKey":"sk-mock-openai"}`
	aad := chOpenAIID

	hexEnv := encryptEnvelopeHex(key, plaintext, aad)
	raw, err := hex.DecodeString(hexEnv)
	if err != nil {
		t.Fatalf("hex decode failed: %v", err)
	}
	if raw[0] != 0x01 {
		t.Fatalf("version byte=%d want 1", raw[0])
	}
	iv := raw[1:13]
	ct := raw[13:]

	block, _ := aes.NewCipher(key)
	gcm, _ := cipher.NewGCM(block)
	decrypted, err := gcm.Open(nil, iv, ct, []byte(aad))
	if err != nil {
		t.Fatalf("gcm decrypt failed: %v", err)
	}
	if string(decrypted) != plaintext {
		t.Fatalf("decrypted=%q want %q", string(decrypted), plaintext)
	}
}
