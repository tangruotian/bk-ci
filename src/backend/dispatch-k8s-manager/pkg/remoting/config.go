package remoting

import (
	"encoding/json"
	"os"

	"github.com/pkg/errors"
)

var RemotingConfig = &RemotingConfigType{}

// RemotingConfigType TODO: 临时调试未来需要api获取的都可以放这里
type RemotingConfigType struct {
	PublicKeys []string `json:"publicKeys"`
	Image      string   `json:"image"`
}

func InitRemotingConfig(path string) error {
	_, err := os.Stat(path)
	if err != nil && os.IsNotExist(err) {
		return errors.Wrap(err, "no remoting config file")
	}

	data, err := os.ReadFile(path)
	if err != nil {
		return errors.Wrap(err, "read remoting config file error")
	}

	err = json.Unmarshal(data, RemotingConfig)
	if err != nil {
		return errors.Wrap(err, "json unmarshal remoting config file error")
	}

	return nil
}
