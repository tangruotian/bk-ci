//go:build windows
// +build windows

package imagedebug

import (
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/envs"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/winprocess"
)

func startTerminalCommand(binary string, args []string, winOptions *api.WinOptions, columns, rows int16) (Terminal, error) {
	options, _, err := imageDebugProcessOptions(winOptions)
	if err != nil {
		return nil, err
	}
	extraEnv := make(map[string]string)
	if envs.GApiEnvVars != nil {
		envs.GApiEnvVars.RangeDo(func(key, value string) bool {
			extraEnv[key] = value
			return true
		})
	}
	options.ExtraEnv = extraEnv
	return winprocess.StartPseudoConsole(options, binary, args, "", columns, rows)
}
