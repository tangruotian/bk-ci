//go:build !windows
// +build !windows

/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package winprocess

import (
	"fmt"
	"os/exec"
)

type LaunchMode int

const (
	LaunchAsCurrent LaunchMode = iota
	LaunchWithPasswordSession0
	LaunchInActiveSession
)

type Options struct {
	Mode                     LaunchMode
	Account                  string
	Password                 string
	Command                  string
	Args                     []string
	CmdLine                  string
	WorkDir                  string
	Env                      []string
	ExtraEnv                 map[string]string
	CreationFlags            uint32
	NoInherit                bool
	Desktop                  string
	TargetUser               string
	LoadProfile              bool
	AllowIdentityEnvOverride bool
}

type ProcessInfo struct {
	PID uint32
}

func (p *ProcessInfo) Close()              {}
func (p *ProcessInfo) CloseProcessHandle() {}
func (p *ProcessInfo) CloseAll()           {}
func (p *ProcessInfo) Wait() (uint32, error) {
	return 0, fmt.Errorf("winprocess is only supported on windows")
}

func Start(_ Options) (*ProcessInfo, error) {
	return nil, fmt.Errorf("winprocess is only supported on windows")
}
func StartCommand(_ *exec.Cmd, _ Options) error {
	return fmt.Errorf("winprocess is only supported on windows")
}
func RunCommand(_ *exec.Cmd, _ Options) error {
	return fmt.Errorf("winprocess is only supported on windows")
}
func GetActiveSessionID() (uint32, error) {
	return 0, fmt.Errorf("winprocess is only supported on windows")
}
func ReadLsaSecret(_ string) (string, error) {
	return "", fmt.Errorf("winprocess is only supported on windows")
}
func ReadSessionCredentials() (user, password string)      { return "", "" }
func SplitUserDomain(account string) (user, domain string) { return account, "." }
