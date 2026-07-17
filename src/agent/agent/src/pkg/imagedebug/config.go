package imagedebug

import "github.com/TencentBlueKing/bk-ci/agent/src/pkg/api"

// CertConfig is configuration of Cert
type CertConfig struct {
	CAFile     string
	CertFile   string
	KeyFile    string
	CertPasswd string
	IsSSL      bool
}

// ConsoleProxyConfig Config is a configuration
type ConsoleProxyConfig struct {
	Address        string
	Port           int
	ServCert       *CertConfig
	DockerEndpoint string
	Privilege      bool
	Cmd            []string
	Tty            bool
	Ips            []string
	IsAuth         bool
	AuthToken      string
	IsOneSeesion   bool
	WinOptions     *api.WinOptions
}

// NewConsoleProxyConfig create a config object
func NewConsoleProxyConfig() ConsoleProxyConfig {
	return ConsoleProxyConfig{
		ServCert: &CertConfig{
			CertPasswd: "",
			IsSSL:      false,
		},
	}
}
