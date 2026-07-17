//go:build windows
// +build windows

package winprocess

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
)

const extendedStartupInfoPresent uint32 = 0x00080000

type PseudoConsole struct {
	input         *os.File
	output        *io.PipeReader
	rawOutput     *os.File
	console       windows.Handle
	processHandle windows.Handle
	cleanup       []func()
	stateMu       sync.Mutex
	finishOnce    sync.Once
	closeOnce     sync.Once
	closeErr      error
}

func StartPseudoConsole(options Options, command string, args []string, workDir string, columns, rows int16) (*PseudoConsole, error) {
	if command == "" {
		return nil, fmt.Errorf("command is required")
	}
	if columns <= 0 {
		columns = 80
	}
	if rows <= 0 {
		rows = 24
	}

	token, cleanup, err := tokenForOptions(options)
	if err != nil {
		return nil, err
	}
	if token != 0 {
		tokenToClose := token
		cleanup = append([]func(){func() { tokenToClose.Close() }}, cleanup...)
	}
	failed := true
	defer func() {
		if failed {
			runCleanup(cleanup)
		}
	}()

	var env []string
	var envCleanup func()
	if token == 0 {
		env = mergeEnv(os.Environ(), options.ExtraEnv)
	} else {
		env, envCleanup, err = environmentForOptions(token)
		if err != nil {
			return nil, err
		}
		if envCleanup != nil {
			defer envCleanup()
		}
		env = mergeEnv(env, options.ExtraEnv)
	}

	appPath, err := resolveExecutableFromEnv(command, env)
	if err != nil {
		return nil, err
	}
	appPtr, err := windows.UTF16PtrFromString(appPath)
	if err != nil {
		return nil, err
	}
	cmdLine, err := windows.UTF16FromString(makeCommandLine(command, args))
	if err != nil {
		return nil, err
	}

	var workDirPtr *uint16
	if workDir != "" {
		workDirPtr, err = windows.UTF16PtrFromString(workDir)
		if err != nil {
			return nil, err
		}
	}

	var inputRead, inputWrite windows.Handle
	if err := windows.CreatePipe(&inputRead, &inputWrite, nil, 0); err != nil {
		return nil, err
	}
	defer func() {
		if inputRead != 0 {
			windows.CloseHandle(inputRead)
		}
		if failed && inputWrite != 0 {
			windows.CloseHandle(inputWrite)
		}
	}()

	var outputRead, outputWrite windows.Handle
	if err := windows.CreatePipe(&outputRead, &outputWrite, nil, 0); err != nil {
		return nil, err
	}
	defer func() {
		if outputWrite != 0 {
			windows.CloseHandle(outputWrite)
		}
		if failed && outputRead != 0 {
			windows.CloseHandle(outputRead)
		}
	}()

	var console windows.Handle
	if err := windows.CreatePseudoConsole(windows.Coord{X: columns, Y: rows}, inputRead, outputWrite, 0, &console); err != nil {
		return nil, fmt.Errorf("CreatePseudoConsole: %w", err)
	}
	defer func() {
		if failed && console != 0 {
			windows.ClosePseudoConsole(console)
		}
	}()

	attributeList, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return nil, err
	}
	defer attributeList.Delete()
	if err := attributeList.Update(
		windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE,
		unsafe.Pointer(console),
		unsafe.Sizeof(console),
	); err != nil {
		return nil, err
	}

	si := windows.StartupInfoEx{
		StartupInfo: windows.StartupInfo{
			Cb:    uint32(unsafe.Sizeof(windows.StartupInfoEx{})),
			Flags: windows.STARTF_USESTDHANDLES,
		},
		ProcThreadAttributeList: attributeList.List(),
	}
	envBlock := buildEnvBlock(env)
	var envPtr *uint16
	if len(envBlock) > 0 {
		envPtr = &envBlock[0]
	}
	flags := options.CreationFlags | CreateUnicodeEnvironment | extendedStartupInfoPresent
	var pi windows.ProcessInformation
	if token != 0 {
		err = windows.CreateProcessAsUser(
			token, appPtr, &cmdLine[0], nil, nil, false, flags,
			envPtr, workDirPtr, &si.StartupInfo, &pi,
		)
	} else {
		err = windows.CreateProcess(
			appPtr, &cmdLine[0], nil, nil, false, flags,
			envPtr, workDirPtr, &si.StartupInfo, &pi,
		)
	}
	if err != nil {
		return nil, fmt.Errorf("create ConPTY process: %w", err)
	}
	windows.CloseHandle(pi.Thread)

	windows.CloseHandle(inputRead)
	inputRead = 0
	windows.CloseHandle(outputWrite)
	outputWrite = 0

	var waitHandle windows.Handle
	if err := windows.DuplicateHandle(
		windows.CurrentProcess(), pi.Process,
		windows.CurrentProcess(), &waitHandle,
		0, false, windows.DUPLICATE_SAME_ACCESS,
	); err != nil {
		_ = windows.TerminateProcess(pi.Process, 1)
		windows.CloseHandle(pi.Process)
		return nil, fmt.Errorf("duplicate ConPTY process handle: %w", err)
	}

	failed = false
	outputReader, outputWriter := io.Pipe()
	pseudoConsole := &PseudoConsole{
		input:         os.NewFile(uintptr(inputWrite), "conpty-input"),
		output:        outputReader,
		rawOutput:     os.NewFile(uintptr(outputRead), "conpty-output"),
		console:       console,
		processHandle: pi.Process,
		cleanup:       cleanup,
	}
	go func() {
		_, copyErr := io.Copy(outputWriter, pseudoConsole.rawOutput)
		_ = outputWriter.CloseWithError(copyErr)
		_ = pseudoConsole.rawOutput.Close()
	}()
	go func() {
		_, _ = windows.WaitForSingleObject(waitHandle, windows.INFINITE)
		windows.CloseHandle(waitHandle)
		pseudoConsole.finish(false)
	}()
	return pseudoConsole, nil
}

func (p *PseudoConsole) Read(buffer []byte) (int, error) {
	return p.output.Read(buffer)
}

func (p *PseudoConsole) Write(buffer []byte) (int, error) {
	return p.input.Write(buffer)
}

func (p *PseudoConsole) Resize(columns, rows int16) error {
	if columns <= 0 || rows <= 0 {
		return fmt.Errorf("invalid pseudo console size: %dx%d", columns, rows)
	}
	p.stateMu.Lock()
	defer p.stateMu.Unlock()
	if p.console == 0 {
		return fmt.Errorf("pseudo console is closed")
	}
	return windows.ResizePseudoConsole(p.console, windows.Coord{X: columns, Y: rows})
}

func (p *PseudoConsole) finish(terminate bool) {
	p.finishOnce.Do(func() {
		p.stateMu.Lock()
		defer p.stateMu.Unlock()
		if p.input != nil {
			p.closeErr = p.input.Close()
		}
		if p.processHandle != 0 {
			if terminate {
				_ = windows.TerminateProcess(p.processHandle, 1)
			}
			_, _ = windows.WaitForSingleObject(p.processHandle, windows.INFINITE)
			windows.CloseHandle(p.processHandle)
			p.processHandle = 0
		}
		if p.console != 0 {
			windows.ClosePseudoConsole(p.console)
			p.console = 0
		}
		runCleanup(p.cleanup)
		p.cleanup = nil
	})
}

func (p *PseudoConsole) Close() error {
	p.closeOnce.Do(func() {
		p.finish(true)
		if p.output != nil {
			if err := p.output.Close(); p.closeErr == nil {
				p.closeErr = err
			}
		}
		if p.rawOutput != nil {
			_ = p.rawOutput.Close()
		}
	})
	return p.closeErr
}

func resolveExecutableFromEnv(command string, env []string) (string, error) {
	if filepath.IsAbs(command) || strings.ContainsAny(command, `\\/`) {
		return command, nil
	}
	pathExt := envValue(env, "PATHEXT")
	if pathExt == "" {
		pathExt = ".COM;.EXE;.BAT;.CMD"
	}
	extensions := []string{""}
	if filepath.Ext(command) == "" {
		extensions = filepath.SplitList(pathExt)
	}
	for _, dir := range filepath.SplitList(envValue(env, "PATH")) {
		if dir == "" {
			continue
		}
		for _, ext := range extensions {
			candidate := filepath.Join(dir, command+ext)
			info, err := os.Stat(candidate)
			if err == nil && !info.IsDir() {
				return candidate, nil
			}
		}
	}
	return "", fmt.Errorf("executable %s not found in target user PATH", command)
}
