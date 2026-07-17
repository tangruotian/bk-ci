//go:build windows
// +build windows

/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package winprocess

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"unsafe"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/common/logs"
	"github.com/sirupsen/logrus"
	"golang.org/x/sys/windows"
)

var (
	modkernel32 = windows.NewLazySystemDLL("kernel32.dll")
	modadvapi32 = windows.NewLazySystemDLL("advapi32.dll")
	moduserenv  = windows.NewLazySystemDLL("userenv.dll")

	procCreateProcessW          = modkernel32.NewProc("CreateProcessW")
	procDuplicateTokenEx        = modadvapi32.NewProc("DuplicateTokenEx")
	procCreateProcessAsUserW    = modadvapi32.NewProc("CreateProcessAsUserW")
	procLogonUserW              = modadvapi32.NewProc("LogonUserW")
	procCreateEnvironmentBlock  = moduserenv.NewProc("CreateEnvironmentBlock")
	procDestroyEnvironmentBlock = moduserenv.NewProc("DestroyEnvironmentBlock")
	procLoadUserProfileW        = moduserenv.NewProc("LoadUserProfileW")
	procUnloadUserProfile       = moduserenv.NewProc("UnloadUserProfile")
)

const (
	CreateUnicodeEnvironment uint32 = 0x00000400
	CreateNoWindow           uint32 = 0x08000000

	securityImpersonation = 2
	tokenPrimary          = 1

	logon32LogonInteractive = 2
	logon32ProviderDefault  = 0
)

type LaunchMode int

const (
	LaunchAsCurrent LaunchMode = iota
	LaunchWithPasswordSession0
	LaunchInActiveSession
)

type Options struct {
	Mode          LaunchMode
	Account       string
	Password      string
	Command       string
	Args          []string
	CmdLine       string
	WorkDir       string
	ExtraEnv      map[string]string
	CreationFlags uint32
	NoInherit     bool
	Desktop       string
	TargetUser    string
	LogCallBack   func(msg string, level logrus.Level)
}

func (o *Options) log(msg string, level logrus.Level) {
	if o.LogCallBack == nil {
		logs.Log(level, o.formatMsg(msg))
	} else {
		o.LogCallBack(o.formatMsg(msg), level)
	}
}
func (o *Options) formatMsg(msg string) string { return fmt.Sprintf("[WIN_PROCESS] %s", msg) }

func (o *Options) Debug(msg string) { o.log(msg, logrus.DebugLevel) }
func (o *Options) Info(msg string)  { o.log(msg, logrus.InfoLevel) }
func (o *Options) Warn(msg string)  { o.log(msg, logrus.WarnLevel) }
func (o *Options) Error(msg string) { o.log(msg, logrus.ErrorLevel) }

type ProcessInfo struct {
	PID           uint32
	ProcessHandle windows.Handle
	ThreadHandle  windows.Handle
	cleanup       []func()
}

func (p *ProcessInfo) Close() {
	if p.ThreadHandle != 0 {
		windows.CloseHandle(p.ThreadHandle)
		p.ThreadHandle = 0
	}
	for i := len(p.cleanup) - 1; i >= 0; i-- {
		p.cleanup[i]()
	}
	p.cleanup = nil
}

func (p *ProcessInfo) CloseProcessHandle() {
	if p.ProcessHandle != 0 {
		windows.CloseHandle(p.ProcessHandle)
		p.ProcessHandle = 0
	}
}

func (p *ProcessInfo) CloseAll() {
	p.CloseProcessHandle()
	p.Close()
}

func (p *ProcessInfo) Wait() (uint32, error) {
	if p.ProcessHandle == 0 {
		return 0, fmt.Errorf("process handle is closed")
	}
	_, err := windows.WaitForSingleObject(p.ProcessHandle, windows.INFINITE)
	if err != nil {
		return 0, err
	}
	var exitCode uint32
	if err := windows.GetExitCodeProcess(p.ProcessHandle, &exitCode); err != nil {
		return 0, err
	}
	return exitCode, nil
}

func StartSession(options Options) (*ProcessInfo, error) {
	options.Mode = LaunchInActiveSession
	if options.Command == "" {
		return nil, fmt.Errorf("command is required")
	}
	cmdLine := options.CmdLine
	if cmdLine == "" {
		cmdLine = makeCommandLine(options.Command, options.Args)
	}
	return startProcess(options, options.Command, cmdLine)
}

func RunCommand(cmd *exec.Cmd, options Options) error {
	if cmd == nil {
		return fmt.Errorf("cmd is nil")
	}
	if options.Mode == LaunchAsCurrent {
		applyCommandOptions(cmd, options)
		if len(options.ExtraEnv) > 0 {
			baseEnv := cmd.Env
			if baseEnv == nil {
				baseEnv = os.Environ()
			}
			cmd.Env = mergeEnv(baseEnv, options.ExtraEnv)
		}
		return cmd.Run()
	}

	token, cleanup, err := tokenForOptions(options)
	if err != nil {
		return err
	}
	if token != 0 {
		tokenToClose := token
		cleanup = append([]func(){func() { tokenToClose.Close() }}, cleanup...)
	}
	defer runCleanup(cleanup)

	env, envCleanup, err := environmentForOptions(token)
	if err != nil {
		return err
	}
	if envCleanup != nil {
		defer envCleanup()
	}
	cmd.Env = mergeEnv(env, options.ExtraEnv)
	if err := resolveCommandPathFromEnv(cmd, cmd.Env); err != nil {
		return err
	}

	applyCommandOptions(cmd, options)
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.Token = syscall.Token(token)
	if cmd.SysProcAttr.CreationFlags&CreateNoWindow == 0 {
		cmd.SysProcAttr.CreationFlags |= CreateNoWindow
	}
	return cmd.Run()
}

func StartCommand(cmd *exec.Cmd, options Options) error {
	if cmd == nil {
		return fmt.Errorf("cmd is nil")
	}
	if options.Mode == LaunchAsCurrent {
		applyCommandOptions(cmd, options)
		options.Info("use Current user run worker process")
		return cmd.Start()
	}

	launchOptions := optionsFromCommand(cmd, options)
	proc, err := startProcess(launchOptions, cmd.Path, commandLineFromCommand(cmd))
	if err != nil {
		return err
	}

	process, err := os.FindProcess(int(proc.PID))
	if err != nil {
		windows.TerminateProcess(proc.ProcessHandle, 1)
		proc.CloseAll()
		return err
	}
	cmd.Process = process
	go func() {
		windows.WaitForSingleObject(proc.ProcessHandle, windows.INFINITE)
		proc.CloseAll()
	}()
	return nil
}

func applyCommandOptions(cmd *exec.Cmd, options Options) {
	if options.WorkDir != "" {
		cmd.Dir = options.WorkDir
	}
	if cmd.SysProcAttr == nil {
		cmd.SysProcAttr = &syscall.SysProcAttr{}
	}
	cmd.SysProcAttr.CreationFlags |= options.CreationFlags
	cmd.SysProcAttr.NoInheritHandles = cmd.SysProcAttr.NoInheritHandles || options.NoInherit
}

func optionsFromCommand(cmd *exec.Cmd, options Options) Options {
	launchOptions := options
	if launchOptions.Command == "" {
		launchOptions.Command = cmd.Path
	}
	if launchOptions.WorkDir == "" {
		launchOptions.WorkDir = cmd.Dir
	}
	if cmd.SysProcAttr != nil {
		launchOptions.CreationFlags |= cmd.SysProcAttr.CreationFlags
		launchOptions.NoInherit = launchOptions.NoInherit || cmd.SysProcAttr.NoInheritHandles
	}
	return launchOptions
}

func startProcess(options Options, appPath, cmdLine string) (*ProcessInfo, error) {
	token, cleanup, err := tokenForOptions(options)
	if err != nil {
		return nil, err
	}
	if token != 0 {
		tokenToClose := token
		cleanup = append([]func(){func() { tokenToClose.Close() }}, cleanup...)
	}

	env, envCleanup, err := environmentForOptions(token)
	if err != nil {
		runCleanup(cleanup)
		return nil, err
	}
	if envCleanup != nil {
		defer envCleanup()
	}
	env = mergeEnv(env, options.ExtraEnv)

	flags := options.CreationFlags | CreateUnicodeEnvironment
	if options.Mode != LaunchAsCurrent && flags&CreateNoWindow == 0 {
		flags |= CreateNoWindow
	}
	if options.Mode == LaunchInActiveSession && options.Desktop == "" {
		options.Desktop = "winsta0\\default"
	}

	proc, err := createProcess(token, appPath, cmdLine, options.WorkDir, env, flags, !options.NoInherit, options.Desktop)
	if err != nil {
		runCleanup(cleanup)
		return nil, err
	}
	proc.cleanup = append(proc.cleanup, cleanup...)
	return proc, nil
}

func tokenForOptions(options Options) (windows.Token, []func(), error) {
	switch options.Mode {
	case LaunchAsCurrent:
		return 0, nil, nil
	case LaunchWithPasswordSession0:
		return tokenFromPassword(options.Account, options.Password)
	case LaunchInActiveSession:
		sessionID, err := RecoverActiveSessionID(options.TargetUser)
		if err != nil {
			return 0, nil, fmt.Errorf("recover active session: %w", err)
		}
		token, err := DuplicateUserToken(sessionID)
		if err != nil {
			return 0, nil, fmt.Errorf("duplicate user token for session %d: %w", sessionID, err)
		}
		return token, nil, nil
	default:
		return 0, nil, fmt.Errorf("unsupported launch mode: %d", options.Mode)
	}
}

func tokenFromPassword(account, password string) (windows.Token, []func(), error) {
	user, domain := SplitUserDomain(account)
	var logonToken windows.Handle
	ret, _, err := procLogonUserW.Call(
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(user))),
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(domain))),
		uintptr(unsafe.Pointer(windows.StringToUTF16Ptr(password))),
		uintptr(logon32LogonInteractive),
		uintptr(logon32ProviderDefault),
		uintptr(unsafe.Pointer(&logonToken)),
	)
	if ret == 0 {
		return 0, nil, fmt.Errorf("LogonUserW(%s): %w", user, err)
	}
	defer windows.CloseHandle(logonToken)

	var primaryToken windows.Token
	ret, _, err = procDuplicateTokenEx.Call(
		uintptr(logonToken),
		0,
		0,
		uintptr(securityImpersonation),
		uintptr(tokenPrimary),
		uintptr(unsafe.Pointer(&primaryToken)),
	)
	if ret == 0 {
		return 0, nil, fmt.Errorf("DuplicateTokenEx: %w", err)
	}

	cleanup := []func(){}
	profile, err := loadUserProfile(primaryToken, user)
	if err != nil {
		primaryToken.Close()
		return 0, nil, err
	}
	cleanup = append(cleanup, func() {
		procUnloadUserProfile.Call(uintptr(primaryToken), uintptr(profile))
	})
	return primaryToken, cleanup, nil
}

func loadUserProfile(token windows.Token, user string) (windows.Handle, error) {
	info := profileInfo{
		Size:     uint32(unsafe.Sizeof(profileInfo{})),
		UserName: windows.StringToUTF16Ptr(user),
	}
	ret, _, err := procLoadUserProfileW.Call(
		uintptr(token),
		uintptr(unsafe.Pointer(&info)),
	)
	if ret == 0 {
		return 0, fmt.Errorf("LoadUserProfileW(%s): %w", user, err)
	}
	return info.Profile, nil
}

type profileInfo struct {
	Size        uint32
	Flags       uint32
	UserName    *uint16
	ProfilePath *uint16
	DefaultPath *uint16
	ServerName  *uint16
	PolicyPath  *uint16
	Profile     windows.Handle
}

func environmentForOptions(token windows.Token) ([]string, func(), error) {
	if token == 0 {
		return nil, nil, nil
	}
	return CreateEnvironment(token)
}

func createProcess(token windows.Token, appPath, cmdLine, workDir string, env []string, flags uint32, inheritHandles bool, desktop string) (*ProcessInfo, error) {
	appPtr, err := windows.UTF16PtrFromString(appPath)
	if err != nil {
		return nil, err
	}
	cmdLineBuf, err := windows.UTF16FromString(cmdLine)
	if err != nil {
		return nil, err
	}
	var cmdLinePtr *uint16
	if len(cmdLineBuf) > 0 {
		cmdLinePtr = &cmdLineBuf[0]
	}
	var workDirPtr *uint16
	if workDir != "" {
		workDirPtr, err = windows.UTF16PtrFromString(workDir)
		if err != nil {
			return nil, err
		}
	}
	envBlock := buildEnvBlock(env)
	var envPtr uintptr
	if len(envBlock) > 0 {
		envPtr = uintptr(unsafe.Pointer(&envBlock[0]))
	}
	var desktopPtr *uint16
	if desktop != "" {
		desktopPtr, err = windows.UTF16PtrFromString(desktop)
		if err != nil {
			return nil, err
		}
	}

	si := windows.StartupInfo{
		Cb:      uint32(unsafe.Sizeof(windows.StartupInfo{})),
		Desktop: desktopPtr,
	}
	var pi windows.ProcessInformation
	inherit := uintptr(0)
	if inheritHandles {
		inherit = 1
	}

	var ret uintptr
	var callErr error
	if token != 0 {
		ret, _, callErr = procCreateProcessAsUserW.Call(
			uintptr(token),
			uintptr(unsafe.Pointer(appPtr)),
			uintptr(unsafe.Pointer(cmdLinePtr)),
			0, 0, inherit,
			uintptr(flags),
			envPtr,
			uintptr(unsafe.Pointer(workDirPtr)),
			uintptr(unsafe.Pointer(&si)),
			uintptr(unsafe.Pointer(&pi)),
		)
	} else {
		ret, _, callErr = procCreateProcessW.Call(
			uintptr(unsafe.Pointer(appPtr)),
			uintptr(unsafe.Pointer(cmdLinePtr)),
			0, 0, inherit,
			uintptr(flags),
			envPtr,
			uintptr(unsafe.Pointer(workDirPtr)),
			uintptr(unsafe.Pointer(&si)),
			uintptr(unsafe.Pointer(&pi)),
		)
	}
	if ret == 0 {
		return nil, fmt.Errorf("CreateProcess: %w", callErr)
	}
	return &ProcessInfo{PID: pi.ProcessId, ProcessHandle: pi.Process, ThreadHandle: pi.Thread}, nil
}

func resolveCommandPathFromEnv(cmd *exec.Cmd, env []string) error {
	if cmd == nil || cmd.Path == "" {
		return nil
	}
	command := cmd.Path
	if len(cmd.Args) > 0 && cmd.Args[0] != "" {
		command = cmd.Args[0]
	}
	if filepath.IsAbs(command) || strings.ContainsAny(command, `\\/`) {
		if cmd.Err != nil && !errors.Is(cmd.Err, exec.ErrNotFound) {
			return cmd.Err
		}
		return nil
	}
	resolved, err := resolveExecutableFromEnv(command, env)
	if err == nil {
		cmd.Path = resolved
		cmd.Err = nil
		return nil
	}
	if cmd.Err != nil {
		return cmd.Err
	}
	return err
}

func envValue(env []string, key string) string {
	for i := len(env) - 1; i >= 0; i-- {
		envKey, ok := splitEnvKey(env[i])
		if ok && strings.EqualFold(envKey, key) {
			return env[i][len(envKey)+1:]
		}
	}
	return ""
}

func makeCommandLine(command string, args []string) string {
	parts := make([]string, 0, len(args)+1)
	parts = append(parts, syscall.EscapeArg(command))
	for _, arg := range args {
		parts = append(parts, syscall.EscapeArg(arg))
	}
	return strings.Join(parts, " ")
}

func commandLineFromCommand(cmd *exec.Cmd) string {
	if cmd.SysProcAttr != nil && cmd.SysProcAttr.CmdLine != "" {
		return cmd.SysProcAttr.CmdLine
	}
	if len(cmd.Args) == 0 {
		return syscall.EscapeArg(cmd.Path)
	}
	parts := make([]string, 0, len(cmd.Args))
	for _, arg := range cmd.Args {
		parts = append(parts, syscall.EscapeArg(arg))
	}
	return strings.Join(parts, " ")
}

func runCleanup(cleanup []func()) {
	for i := len(cleanup) - 1; i >= 0; i-- {
		cleanup[i]()
	}
}
