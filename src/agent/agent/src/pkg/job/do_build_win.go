//go:build windows
// +build windows

/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package job

import (
	"fmt"
	"os"
	"os/exec"
	"syscall"

	"github.com/pkg/errors"
	"github.com/sirupsen/logrus"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/common/logs"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/common/utils/fileutil"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/config"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/constant"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/envs"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/i18n"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/process"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/systemutil"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/winprocess"
	"github.com/TencentBlueKing/bk-ci/agent/src/third_components"
)

// 方便测试
var startWorkerCommand = winprocess.StartCommand

func doBuild(
	buildInfo *api.ThirdPartyBuildInfo,
	tmpDir string,
	workDir string,
	goEnv map[string]string,
	runUser string,
) error {
	goEnv["DEVOPS_AGENT_INSTALL_MODE"] = config.GAgentEnv.InstallType
	var err error
	var exitGroup process.ProcessExitGroup
	enableExitGroup := envs.FetchEnvAndCheck(constant.DevopsAgentEnableExitGroup, "true")
	if enableExitGroup {
		logs.Info("DEVOPS_AGENT_ENABLE_EXIT_GROUP enable")
		exitGroup, err = process.NewProcessExitGroup()
		if err != nil {
			errMsg := i18n.Localize("StartWorkerProcessFailed", map[string]interface{}{"err": err.Error()})
			logs.Error(errMsg)
			workerBuildFinish(buildInfo.ToFinish(false, errMsg, api.BuildProcessStartErrorEnum))
			return err
		}

		defer func() {
			logs.Infof("%s exit group dispose", buildInfo.BuildId)
			exitGroup.Dispose()
		}()
	}

	startCmd := third_components.GetJavaLatest()
	agentLogPrefix := fmt.Sprintf("%s_%s_agent", buildInfo.BuildId, buildInfo.VmSeqId)
	errorMsgFile := getWorkerErrorMsgFile(buildInfo.BuildId, buildInfo.VmSeqId)
	args := []string{
		"-Djava.io.tmpdir=" + tmpDir,
		"-Ddevops.agent.error.file=" + errorMsgFile,
		"-Dbuild.type=AGENT",
		"-DAGENT_LOG_PREFIX=" + agentLogPrefix,
		"-Xmx2g", // #5806 兼容性问题，必须独立一行
		"-jar",
		config.BuildAgentJarPath(),
		getEncodedBuildInfo(buildInfo.WorkerBuildInfo())}
	cmd, err := StartProcessCmd(buildInfo.WinOptions, startCmd, args, workDir, goEnv, func(msg string, level logrus.Level) {
		logCallBack(msg, level, buildInfo)
	})
	if err != nil {
		errMsg := i18n.Localize("StartWorkerProcessFailed", map[string]interface{}{"err": err.Error()})
		logs.Error(errMsg)
		workerBuildFinish(buildInfo.ToFinish(false, errMsg, api.BuildProcessStartErrorEnum))
		return err
	}
	pid := cmd.Process.Pid

	if enableExitGroup {
		logs.Infof("%s process %d add exit group ", buildInfo.BuildId, pid)
		if err := exitGroup.AddProcess(cmd.Process); err != nil {
			logs.Errorf("%s add process  to %d exit group error %s", buildInfo.BuildId, pid, err.Error())
		}
	}

	// 添加需要构建结束后删除的文件
	buildInfo.ToDelTmpFiles = []string{errorMsgFile}

	GBuildManager.AddBuild(pid, buildInfo)
	logs.Info(fmt.Sprintf("[%s]|Job#_%s|Build started, pid:%d ", buildInfo.BuildId, buildInfo.VmSeqId, pid))

	// #5806 预先录入异常信息，在构建进程正常结束时清理掉。如果没清理掉，则说明进程非正常退出，可能被OS或人为杀死
	_ = fileutil.WriteString(errorMsgFile, i18n.Localize("BuilderProcessWasKilled", nil))
	_ = systemutil.Chmod(errorMsgFile, os.ModePerm)

	err = cmd.Wait()
	// #5806 从b-xxxx_build_msg.log 读取错误信息，此信息可由worker-agent.jar写入，用于当异常时能够将信息上报给服务器
	msgFile := getWorkerErrorMsgFile(buildInfo.BuildId, buildInfo.VmSeqId)
	msg, _ := fileutil.GetString(msgFile)
	if err != nil {
		logs.Errorf("build[%s] pid[%d] finish, state=%v err=%v, msg=%s", buildInfo.BuildId, pid, cmd.ProcessState, err, msg)
	} else {
		logs.Infof("build[%s] pid[%d] finish, state=%v err=%v, msg=%s", buildInfo.BuildId, pid, cmd.ProcessState, err, msg)
	}

	// #10362 Worker杀掉当前进程父进程导致Agent误报
	// agent 改动后可能会导致业务执行完成但是进程被杀掉导致流水线错误，所以将错误只是作为额外信息添加
	cmdErrMsg := ""
	if err != nil {
		cmdErrMsg = "|" + err.Error()
	}

	success := true
	if len(msg) == 0 {
		msg = i18n.Localize("WorkerExit", map[string]interface{}{"pid": pid}) + cmdErrMsg
	} else {
		msg += cmdErrMsg
		success = false
	}

	GBuildManager.DeleteBuild(pid)
	if success {
		workerBuildFinish(buildInfo.ToFinish(success, msg, api.NoErrorEnum))
	} else {
		workerBuildFinish(buildInfo.ToFinish(success, msg, api.BuildProcessRunErrorEnum))
	}

	return nil
}

func StartProcessCmd(
	winOptions *api.WinOptions,
	command string,
	args []string,
	workDir string,
	envMap map[string]string,
	logCallBack func(string, logrus.Level),
) (*exec.Cmd, error) {
	cmd := exec.Command(command)

	// DEVOPS_AGENT_CLOSE_FD_INHERIT: optional fd isolation matching Windows' NoInheritHandles.
	sysProcAttr := &syscall.SysProcAttr{
		NoInheritHandles: false,
	}
	// 非默认模式启动的worker不能继承句柄,因为agent和worker的用户环境已经不统一了
	if envs.FetchEnvAndCheck(constant.DevopsAgentCloseFdInherit, "true") ||
		(winOptions != nil && winOptions.NoDefaultMod()) {
		sysProcAttr.NoInheritHandles = true
		logs.Info("DEVOPS_AGENT_CLOSE_FD_INHERIT enabled: fd isolation for build process")
	}
	if envs.FetchEnvAndCheck(constant.DevopsAgentEnableNewConsole, "true") {
		sysProcAttr.CreationFlags = constant.WinCommandNewConsole
		logs.Info("DEVOPS_AGENT_ENABLE_NEW_CONSOLE enabled")
	}
	cmd.SysProcAttr = sysProcAttr

	if len(args) > 0 {
		cmd.Args = append(cmd.Args, args...)
	}

	if workDir != "" {
		cmd.Dir = workDir
	}

	cmd.Env = envs.Envs()
	for k, v := range envMap {
		cmd.Env = append(cmd.Env, fmt.Sprintf("%s=%s", k, v))
	}

	logs.Info("cmd.Path: ", cmd.Path)
	logs.Info("cmd.Args: ", cmd.Args)
	logs.Info("cmd.workDir: ", cmd.Dir)

	options := winprocess.Options{Mode: winprocess.LaunchAsCurrent, LogCallBack: logCallBack}
	if winOptions != nil {
		if winOptions.BuildMod == string(api.WinOptionModUI) {
			cmd.Env = nil
			options = winprocess.Options{
				Mode:        winprocess.LaunchInActiveSession,
				TargetUser:  winOptions.UserName,
				LogCallBack: logCallBack,
				ExtraEnv:    envMap,
			}
			options.Info("use UI run worker process")
		} else if winOptions.BuildMod == string(api.WinOptionModLogin) {
			if winOptions.Credential.ErrMsg != "" {
				logs.Error("WIN_JOBG|get cred error ", winOptions.Credential.ErrMsg)
				return nil, errors.New("get win options cred error")
			}
			cmd.Env = nil
			options = winprocess.Options{
				Mode:        winprocess.LaunchWithPasswordSession0,
				Account:     winOptions.Credential.User,
				Password:    winOptions.Credential.Password,
				LogCallBack: logCallBack,
				ExtraEnv:    envMap,
			}
			options.Info("use password Login run worker process")
		}
	}

	err := startWorkerCommand(cmd, options)
	if err != nil {
		return nil, err
	}

	return cmd, nil
}

func logCallBack(msg string, level logrus.Level, buildInfo *api.ThirdPartyBuildInfo) {
	switch level {
	case logrus.ErrorLevel:
		postLog(true, msg, buildInfo, api.LogtypeError)
	case logrus.WarnLevel:
		postLog(false, msg, buildInfo, api.LogtypeWarn)
	case logrus.DebugLevel:
		postLog(false, msg, buildInfo, api.LogtypeDebug)
	default:
		postLog(false, msg, buildInfo, api.LogtypeLog)
	}
}
