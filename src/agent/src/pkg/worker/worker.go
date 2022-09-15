package worker

import (
	"fmt"
	"github.com/Tencent/bk-ci/src/agent/src/pkg/api"
	"github.com/Tencent/bk-ci/src/agent/src/pkg/logs"
	"github.com/pkg/errors"
	"github.com/sirupsen/logrus"
	"gopkg.in/natefinch/lumberjack.v2"
)

func RunBuild(
	buildType BuildType,
	buildInfo *api.ThirdPartyBuildInfo,
	workerInfo *Info,
) error {
	log, err := initRunnerLog(workerInfo.LogPrefix + ".log")
	if err != nil {
		return errors.Wrap(err, "init log error")
	}

	r := Runner{
		BuildInfo:    *buildInfo,
		Logs:         log,
		ErrorMsgFile: workerInfo.ErrorLogPath,
		TmpDir:       workerInfo.TmpDir,
	}

	switch buildType {
	// TODO: buildType 等待补全其他构建类型
	case WORKER:
		return nil
	case DOCKER:
		return nil
	case AGENT:
		go func() {
			defer func() {
				if err := recover(); err != nil {
					// TODO: 等待补充类似 addErrorMsgWriteToFileHook()
				}
			}()
			err = r.execute(&AgentWorkSpaceImpl{workSpace: r.BuildInfo.Workspace})
			if err != nil {
				// TODO: 等待补充类似 addErrorMsgWriteToFileHook()
			}
		}()
	}

	return nil
}

func initRunnerLog(filePath string) (*logrus.Logger, error) {
	logInfo := logrus.New()

	lumLog := &lumberjack.Logger{
		Filename:   filePath,
		MaxBackups: 14,
		LocalTime:  true,
		MaxSize:    100,
	}

	logInfo.Out = lumLog

	logInfo.SetFormatter(&logs.MyFormatter{})

	return logInfo, nil
}

type Runner struct {
	BuildInfo    api.ThirdPartyBuildInfo
	Logs         *logrus.Logger
	ErrorMsgFile string
	TmpDir       string
}

type WorkSpaceInterface interface {
	GetWorkspaceAndLogDir(variables map[string]string, pipelineId string) (workspaceDir, logPathDir string)
}

type AgentWorkSpaceImpl struct {
	workSpace string
}

func (r *AgentWorkSpaceImpl) GetWorkspaceAndLogDir(variables map[string]string, pipelineId string) (workspaceDir, logPathDir string) {
	replaceWorkspace := r.workSpace
	if r.workSpace != "" {

	}
}

func (r *Runner) execute(w WorkSpaceInterface) error {
	logs.Info(fmt.Sprintf("[%s]|Start worker for build| projectId=%s", r.BuildInfo.BuildId, r.BuildInfo.ProjectId))

	return nil
}
