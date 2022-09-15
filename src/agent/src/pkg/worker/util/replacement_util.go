package util

import (
	"bytes"
	"strings"
)

type KeyReplacement interface {
	GetReplacement(key string) (string, bool)
}

func Replace(command string, k KeyReplacement, contextMap map[string]string) string {

}

// parseTemplate 从左向右找括号，每次只寻找最里面的，避免括号嵌套
func parseTemplate(command string, k KeyReplacement, contextMap map[string]string, dep int) string {
	if dep < 0 {
		return command
	}

	var buffer bytes.Buffer

	match := re.FindAllStringSubmatch(command, -1)
	groups := re.SubexpNames()
	for _, m := range match {
		for j, name := range groups {
			if j != 0 && name != "" {
				key := strings.TrimSpace(m[j])
				value, ok := k.GetReplacement(key)
				if !ok {
					value = contextMap[key]
				}
			}
		}
		result = append(result, m)
	}
}
