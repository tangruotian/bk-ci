//go:build windows
// +build windows

package job

import (
	"fmt"
	"os/exec"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/dockercli"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/envs"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/winprocess"
)

func configureBuildDockerRunner(runner *dockercli.Runner, winOptions *api.WinOptions) error {
	options, enabled, err := dockerProcessOptions(winOptions)
	if err != nil {
		return err
	}
	if !enabled {
		return nil
	}

	extraEnv := make(map[string]string)
	if envs.GApiEnvVars != nil {
		envs.GApiEnvVars.RangeDo(func(key, value string) bool {
			extraEnv[key] = value
			return true
		})
	}
	options.ExtraEnv = extraEnv
	runner.SetCommandRunner(func(cmd *exec.Cmd) error {
		return winprocess.RunCommand(cmd, options)
	})
	return nil
}

func dockerProcessOptions(winOptions *api.WinOptions) (winprocess.Options, bool, error) {
	if winOptions == nil {
		return winprocess.Options{}, false, nil
	}
	switch winOptions.BuildMod {
	case string(api.WinOptionModUI):
		return winprocess.Options{
			Mode:       winprocess.LaunchInActiveSession,
			TargetUser: winOptions.UserName,
		}, true, nil
	case string(api.WinOptionModLogin):
		if winOptions.Credential.ErrMsg != "" {
			return winprocess.Options{}, false, fmt.Errorf("get Windows credential: %s", winOptions.Credential.ErrMsg)
		}
		return winprocess.Options{
			Mode:     winprocess.LaunchWithPasswordSession0,
			Account:  winOptions.Credential.User,
			Password: winOptions.Credential.Password,
		}, true, nil
	default:
		return winprocess.Options{}, false, nil
	}
}
