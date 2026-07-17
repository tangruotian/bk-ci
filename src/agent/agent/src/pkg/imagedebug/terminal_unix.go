//go:build !windows
// +build !windows

package imagedebug

import (
	"os"
	"os/exec"
	"sync"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/creack/pty"
)

type unixTerminal struct {
	file      *os.File
	cmd       *exec.Cmd
	done      chan struct{}
	closeOnce sync.Once
	closeErr  error
}

func startTerminalCommand(binary string, args []string, _ *api.WinOptions, columns, rows int16) (Terminal, error) {
	cmd := exec.Command(binary, args...)
	file, err := pty.Start(cmd)
	if err != nil {
		return nil, err
	}
	terminal := &unixTerminal{file: file, cmd: cmd, done: make(chan struct{})}
	go func() {
		_ = cmd.Wait()
		_ = file.Close()
		close(terminal.done)
	}()
	if columns > 0 && rows > 0 {
		_ = terminal.Resize(columns, rows)
	}
	return terminal, nil
}

func (t *unixTerminal) Read(buffer []byte) (int, error) {
	return t.file.Read(buffer)
}

func (t *unixTerminal) Write(buffer []byte) (int, error) {
	return t.file.Write(buffer)
}

func (t *unixTerminal) Resize(columns, rows int16) error {
	return pty.Setsize(t.file, &pty.Winsize{Rows: uint16(rows), Cols: uint16(columns)})
}

func (t *unixTerminal) Close() error {
	t.closeOnce.Do(func() {
		t.closeErr = t.file.Close()
		if t.cmd.Process != nil {
			_ = t.cmd.Process.Kill()
		}
		<-t.done
	})
	return t.closeErr
}
