//go:build windows
// +build windows

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

package upgrader

import (
	"path/filepath"
	"time"

	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/config"
	"github.com/TencentBlueKing/bk-ci/agent/src/pkg/util/systemutil"
	"github.com/TencentBlueKing/bk-ci/agentcommon/logs"
	"github.com/go-ole/go-ole"
	"github.com/go-ole/go-ole/oleutil"
	"golang.org/x/sys/windows/svc/mgr"
	"gopkg.in/ini.v1"
)

func windowsRestartDaemon() {
	conf, err := ini.Load(filepath.Join(systemutil.GetWorkDir(), ".agent.properties"))
	if err != nil {
		logs.Error("load agent config failed, ", err)
		return
	}
	agentId := conf.Section("").Key(config.KeyAgentId).String()
	if len(agentId) == 0 {
		logs.Error("invalid agentId")
		return
	}
	serviceName := "devops_agent_" + agentId
	ok := startWinService(serviceName)
	if ok {
		return
	}
	startWinScheduleService(serviceName)
}

func startWinService(name string) bool {
	time.Sleep(3*time.Second)
	m, err := mgr.Connect()
	if err != nil {
		logs.Error(err)
		return false
	}
	defer m.Disconnect()
	service, err := m.OpenService(name)
	if err != nil {
		logs.Error(err)
		return false
	}
	err = service.Start()
	if err != nil {
		logs.Error(err)
		return false
	}
	logs.Infof("start % service success", name)
	return true
}

func startWinScheduleService(name string) {
	ole.CoInitialize(0)
	defer ole.CoUninitialize()

	unknown, err := oleutil.CreateObject("Schedule.Service")
	if err != nil {
		logs.Error(err)
		return
	}
	defer unknown.Release()

	service, err := unknown.QueryInterface(ole.IID_IDispatch)
	if err != nil {
		logs.Error(err)
		return
	}
	defer service.Release()

	_, err = oleutil.CallMethod(service, "Connect")
	if err != nil {
		logs.Error(err)
		return
	}

	folder, err := oleutil.CallMethod(service, "GetFolder", "\\")
	if err != nil {
		logs.Error(err)
		return
	}

	task, err := oleutil.CallMethod(folder.ToIDispatch(), "GetTask", name)
	if err != nil {
		logs.Error(err)
		return
	}

	_, err = oleutil.CallMethod(task.ToIDispatch(), "Run", "")
	if err != nil {
		logs.Error(err)
		return
	}

	logs.Infof("start % task success", name)
}
