package password

import "testing"

func TestHashAndVerify(t *testing.T) {
	encoded, err := Hash("correct horse battery staple")
	if err != nil {
		t.Fatalf("hash password: %v", err)
	}

	matched, err := Verify("correct horse battery staple", encoded)
	if err != nil {
		t.Fatalf("verify password: %v", err)
	}
	if !matched {
		t.Fatal("correct password did not match")
	}

	matched, err = Verify("incorrect password", encoded)
	if err != nil {
		t.Fatalf("verify incorrect password: %v", err)
	}
	if matched {
		t.Fatal("incorrect password matched")
	}
}

func TestHashRejectsShortPassword(t *testing.T) {
	if _, err := Hash("short"); err == nil {
		t.Fatal("short password was accepted")
	}
}
