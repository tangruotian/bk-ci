//go:build !windows
// +build !windows

package job

import (
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/dockercli"
)

func configureBuildDockerRunner(_ *dockercli.Runner, _ *api.WinOptions) error {
	return nil
}
