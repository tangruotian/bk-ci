/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
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

package heartbeat

import (
	"context"
	"fmt"
	"net/http"
	"runtime"
	"sync"
	"time"

	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/api"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/config"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/job"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/logs"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/upgrade"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/util"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/util/httputil"
	"github.com/TencentBlueKing/bk-ci/src/agent/src/pkg/util/systemutil"
	"github.com/pkg/errors"
	"golang.org/x/sync/errgroup"
	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"
)

func DoAgentHeartbeat() {
	defer func() {
		if err := recover(); err != nil {
			logs.Error("agent heartbeat panic: ", err)
		}
	}()

	// 部分逻辑只在启动时运行一次
	var jdkOnce = &sync.Once{}
	var dockerfileSyncOnce = &sync.Once{}

	// 先使用websocket做心跳
	err := webSocketHeartBeat(jdkOnce, dockerfileSyncOnce)
	if err != nil {
		logs.Error("websocket error", err)
	}

	// 报错后使用http
	httpHeartBeat(jdkOnce, dockerfileSyncOnce)
}

func webSocketHeartBeat(jdkOnce, dockerfileSyncOnce *sync.Once) error {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	header := http.Header{}
	for k, v := range config.GAgentConfig.GetAuthHeaderMap() {
		header.Add(k, v)
	}

	url := fmt.Sprintf("wss://%s%s", config.GetGateWay(), "/ms/environment/api/build/ws/thirdPartyAgent")
	c, _, err := websocket.Dial(ctx, url, &websocket.DialOptions{
		HTTPHeader: header,
	})
	if err != nil {
		return errors.Wrapf(err, "websocket dial %s error", url)
	}
	defer c.Close(websocket.StatusNormalClosure, "")

	errGroup, ctx := errgroup.WithContext(ctx)

	errGroup.Go(func() error {
		for {
			result := new(httputil.DevopsResult)
			err = wsjson.Read(ctx, c, result)
			if err != nil {
				return errors.Wrap(err, "read websocket message error")
			}

			heartbeatResponse, deleted, err := parseHeartbeatResult(result)
			if err != nil {
				return err
			}
			if deleted {
				return nil
			}

			// agent配置
			changeConfigByHeartbeat(heartbeatResponse)

			logs.Info("agent heartbeat done")
		}
	})

	errGroup.Go(func() error {
		for {
			heartbeatInfo := createHeartbeatInfo(jdkOnce, dockerfileSyncOnce)

			err = wsjson.Write(ctx, c, heartbeatInfo)
			if err != nil {
				return errors.Wrap(err, "write websocket message error")
			}
			time.Sleep(10 * time.Second)
		}
	})

	if err := errGroup.Wait(); err != nil {
		logs.Error("websocket read or write error", err)
	}

	return nil
}

func httpHeartBeat(jdkOnce, dockerfileSyncOnce *sync.Once) {
	for {
		_ = agentHeartbeat(jdkOnce, dockerfileSyncOnce)
		time.Sleep(10 * time.Second)
	}
}

func agentHeartbeat(jdkSyncOnce, dockerfileSyncOnce *sync.Once) error {
	result, err := api.Heartbeat(createHeartbeatInfo(jdkSyncOnce, dockerfileSyncOnce))
	if err != nil {
		logs.Error("agent heartbeat failed: ", err.Error())
		return errors.New("agent heartbeat failed")
	}
	heartbeatResponse, deleted, err := parseHeartbeatResult(result)
	if err != nil {
		return err
	}
	if deleted {
		return nil
	}

	// agent配置
	changeConfigByHeartbeat(heartbeatResponse)

	logs.Info("agent heartbeat done")
	return nil
}

func createHeartbeatInfo(jdkSyncOnce, dockerfileSyncOnce *sync.Once) *api.AgentHeartbeatInfo {
	// 在第一次启动时同步一次jdk version，防止重启时因为upgrade的执行慢导致了升级jdk
	var jdkVersion []string
	jdkSyncOnce.Do(func() {
		version, err := upgrade.SyncJdkVersion()
		if err != nil {
			logs.Error("agent heart sync jdkVersion error", err)
			return
		}
		jdkVersion = version
	})
	if jdkVersion == nil {
		jdkVersion = upgrade.JdkVersion.GetVersion()
	}

	// 获取docker的filemd5前也同步一次
	dockerfileSyncOnce.Do(func() {
		if err := upgrade.SyncDockerInitFileMd5(); err != nil {
			logs.Error("agent heart sync docker file md5 error", err)
		}
	})

	var taskList []api.ThirdPartyTaskInfo
	for _, info := range job.GBuildManager.GetInstances() {
		taskList = append(taskList, api.ThirdPartyTaskInfo{
			ProjectId: info.ProjectId,
			BuildId:   info.BuildId,
			VmSeqId:   info.VmSeqId,
			Workspace: info.Workspace,
		})
	}
	agentHeartbeatInfo := &api.AgentHeartbeatInfo{
		MasterVersion:     config.AgentVersion,
		SlaveVersion:      config.GAgentEnv.SlaveVersion,
		HostName:          config.GAgentEnv.HostName,
		AgentIp:           config.GAgentEnv.AgentIp,
		ParallelTaskCount: config.GAgentConfig.ParallelTaskCount,
		AgentInstallPath:  systemutil.GetExecutableDir(),
		StartedUser:       systemutil.GetCurrentUser().Username,
		TaskList:          taskList,
		Props: api.AgentPropsInfo{
			Arch:       runtime.GOARCH,
			JdkVersion: jdkVersion,
			DockerInitFileMd5: api.DockerInitFileInfo{
				FileMd5:     upgrade.DockerFileMd5.Md5,
				NeedUpgrade: upgrade.DockerFileMd5.NeedUpgrade,
			},
		},
		DockerParallelTaskCount: config.GAgentConfig.DockerParallelTaskCount,
		DockerTaskList:          job.GBuildDockerManager.GetInstances(),
	}

	return agentHeartbeatInfo
}

func parseHeartbeatResult(result *httputil.DevopsResult) (*api.AgentHeartbeatResponse, bool, error) {
	if result.IsNotOk() {
		logs.Error("agent heartbeat failed: ", result.Message)
		return nil, false, errors.New("agent heartbeat failed")
	}

	heartbeatResponse := new(api.AgentHeartbeatResponse)
	err := util.ParseJsonToData(result.Data, &heartbeatResponse)
	if err != nil {
		logs.Error("agent heartbeat failed: ", err.Error())
		return nil, false, errors.New("agent heartbeat failed")
	}

	if heartbeatResponse.AgentStatus == config.AgentStatusDelete {
		upgrade.UninstallAgent()
		return nil, true, nil
	}

	return heartbeatResponse, false, nil
}

func changeConfigByHeartbeat(heartbeatResponse *api.AgentHeartbeatResponse) {
	// agent配置
	configChanged := false
	if config.GAgentConfig.ParallelTaskCount != heartbeatResponse.ParallelTaskCount {
		config.GAgentConfig.ParallelTaskCount = heartbeatResponse.ParallelTaskCount
		configChanged = true
	}
	if heartbeatResponse.Gateway != "" && heartbeatResponse.Gateway != config.GAgentConfig.Gateway {
		config.GAgentConfig.Gateway = heartbeatResponse.Gateway
		systemutil.DevopsGateway = heartbeatResponse.Gateway
		configChanged = true
	}
	if heartbeatResponse.FileGateway != "" && heartbeatResponse.FileGateway != config.GAgentConfig.FileGateway {
		config.GAgentConfig.FileGateway = heartbeatResponse.FileGateway
		configChanged = true
	}
	if config.GAgentConfig.DockerParallelTaskCount != heartbeatResponse.DockerParallelTaskCount {
		config.GAgentConfig.DockerParallelTaskCount = heartbeatResponse.DockerParallelTaskCount
		configChanged = true
	}

	if heartbeatResponse.Props.KeepLogsHours > 0 &&
		config.GAgentConfig.LogsKeepHours != heartbeatResponse.Props.KeepLogsHours {
		config.GAgentConfig.LogsKeepHours = heartbeatResponse.Props.KeepLogsHours
		configChanged = true
	}

	if heartbeatResponse.Props.IgnoreLocalIps != "" &&
		config.GAgentConfig.IgnoreLocalIps != heartbeatResponse.Props.IgnoreLocalIps {
		config.GAgentConfig.IgnoreLocalIps = heartbeatResponse.Props.IgnoreLocalIps
		configChanged = true
	}

	if configChanged {
		_ = config.GAgentConfig.SaveConfig()
	}

	// agent环境变量
	config.GEnvVars = heartbeatResponse.Envs

	/*
	   忽略一些在Windows机器上VPN代理软件所产生的虚拟网卡（有Mac地址）的IP，一般这类IP
	   更像是一些路由器的192开头的IP，属于干扰IP，安装了这类软件的windows机器IP都会变成相同，所以需要忽略掉
	*/
	if len(config.GAgentConfig.IgnoreLocalIps) > 0 {
		splitIps := util.SplitAndTrimSpace(config.GAgentConfig.IgnoreLocalIps, ",")
		if util.Contains(splitIps, config.GAgentEnv.AgentIp) { // Agent检测到的IP与要忽略的本地VPN IP相同，则更换真正IP
			config.GAgentEnv.AgentIp = systemutil.GetAgentIp(splitIps)
		}
	}

	// 检测agent版本与agent文件是否匹配
	if config.AgentVersion != heartbeatResponse.MasterVersion {
		agentFileVersion := config.DetectAgentVersion()
		if agentFileVersion != "" && config.AgentVersion != agentFileVersion {
			logs.Warn("agent version mismatch, exiting agent process")
			systemutil.ExitProcess(1)
		}
	}
}
