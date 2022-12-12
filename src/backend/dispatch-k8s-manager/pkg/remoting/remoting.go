package remoting

// TODO: 调试使用目前先写死
const (
	VolumeName                     = "remoting-host"
	VolumeMountPath                = "/data/landun/workspace"
	HostPath                       = "/data/landun/workspace"
	RemotingWorkspaceCoreLabel     = "bkci.dispatch.kubenetes.remoting/workspaceID"
	RemotingWatchLabel             = "bkci.dispatch.kubenetes.remoting/watch-task"
	RemotingVscodeWebPort          = 23000
	RemotingVscodeSSHPort          = 23001
	RemotingApiPort                = 22999
	RemotingServiceWebNodePortName = "webvscodeport"
	RemotingServiceSSHNodePortName = "sshport"
	RemotingServiceApiNodePortName = "apiport"
)

const (
	KubernetesCoreLabelName      = "bkci.dispatch.kubenetes.remoting/core"
	KubernetesCoreLabelNameValue = "workspace"
	KubernetesOwnerLabel         = "bkci.dispatch.kubenetes.remoting/owner"

	KubernetesWorkspaceSSHPublicKeys = "bkci.dispatch.kubenetes.remoting/sshPublicKeys"
)

type SSHPublicKeys struct {
	Keys []string `json:"keys,omitempty"`
}
