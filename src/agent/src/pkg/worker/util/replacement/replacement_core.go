//go:build core
// +build core

package replacement

import "bytes"

// parseTemplate 从左向右找括号，每次只寻找最里面的，避免括号嵌套
func parseTemplate(command string, k KeyReplacement, contextMap map[string]string, dep int) string {
	blockes := findExpressions(command, 1)
	if blockes == nil || len(blockes) == 0 {
		return command
	}

	var buffer bytes.Buffer
	chars := []rune(command)

}
