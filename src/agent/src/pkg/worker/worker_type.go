package worker

type BuildType string

const (
	DOCKER BuildType = "DOCKER"
	WORKER BuildType = "WORKER"
	AGENT  BuildType = "AGENT"
)

type Info struct {
	ErrorLogPath string
	LogPrefix    string
	TmpDir       string
}
