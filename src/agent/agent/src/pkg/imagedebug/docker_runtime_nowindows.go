//go:build !windows
// +build !windows

package imagedebug

import (
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/dockercli"
)

func configureImageDebugDockerRunner(_ *dockercli.Runner, _ *api.WinOptions) error {
	return nil
}
