package task

import (
	"disaptch-k8s-manager/pkg/kubeclient"
	"disaptch-k8s-manager/pkg/logs"

	"github.com/pkg/errors"
)

func DoCreateWorkspace(taskId string, workspace *kubeclient.Deployment) {
	err := kubeclient.CreateDeployment(workspace)
	if err != nil {
		failTask(taskId, errors.Wrap(err, "create remoting deployment error").Error())
		return
	}

	err = kubeclient.CreateRemotingNodePortService("service-"+workspace.Name, workspace.Name)
	if err != nil {
		logs.Error("create remoting service error ", err)
		failTask(taskId, errors.Wrap(err, "create remoting service error").Error())
		return
	}
}

func DoDeleteWorkspace(taskId string, workspaceId string) {
	err := kubeclient.DeleteDeployment(workspaceId)
	if err != nil {
		failTask(taskId, errors.Wrap(err, "delete workspace error").Error())
		return
	}

	err = kubeclient.DeleteService("service-" + workspaceId)
	if err != nil {
		failTask(taskId, errors.Wrap(err, "delete workspace error").Error())
		return
	}

	deleteBuilderLinkDbData(workspaceId)

	okTask(taskId)
}
