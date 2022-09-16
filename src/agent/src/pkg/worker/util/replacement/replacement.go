package replacement

import (
	"bytes"
	"strings"
)

type KeyReplacement interface {
	GetReplacement(key string) (string, bool)
}

func Replace(command string, k KeyReplacement, contextMap map[string]string) string {
	if command == "" {
		return command
	}

	var buffer bytes.Buffer

	lines := strings.Split(strings.ReplaceAll(strings.ReplaceAll(command, "\r\n", "\n"), "\r", "\n"), "\n")
	for index, line := range lines {
		template := line
		if strings.HasPrefix(strings.TrimSpace(line), "#") {
			template = parseTemplate(line, k, contextMap, 1)
		}
		buffer.WriteString(template)
		if index != len(lines)-1 {
			buffer.WriteString("\n")
		}
	}

	return buffer.String()
}
