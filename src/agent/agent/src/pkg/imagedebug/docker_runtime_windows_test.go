//go:build windows
// +build windows

package imagedebug

import (
	"testing"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/winprocess"
)

func TestImageDebugProcessOptions(t *testing.T) {
	options, enabled, err := imageDebugProcessOptions(&api.WinOptions{
		BuildMod: string(api.WinOptionModUI),
		UserName: "builduser",
	})
	if err != nil {
		t.Fatal(err)
	}
	if !enabled || options.Mode != winprocess.LaunchInActiveSession || options.TargetUser != "builduser" {
		t.Fatalf("options = %+v, enabled = %v", options, enabled)
	}

	options, enabled, err = imageDebugProcessOptions(&api.WinOptions{
		BuildMod: string(api.WinOptionModLogin),
		Credential: api.Credential{
			User:     "user",
			Password: "secret",
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	if !enabled || options.Mode != winprocess.LaunchWithPasswordSession0 || options.Account != "user" || options.Password != "secret" {
		t.Fatalf("options = %+v, enabled = %v", options, enabled)
	}
}
