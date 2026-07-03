//go:build windows
// +build windows

/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 Tencent.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 */

package winprocess

import (
	"sort"
	"strings"
	"syscall"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

var identityEnvKeys = map[string]struct{}{
	"USERNAME":     {},
	"USERDOMAIN":   {},
	"USERPROFILE":  {},
	"HOMEDRIVE":    {},
	"HOMEPATH":     {},
	"APPDATA":      {},
	"LOCALAPPDATA": {},
}

func CreateEnvironment(token windows.Token) ([]string, func(), error) {
	var envBlock uintptr
	ret, _, err := procCreateEnvironmentBlock.Call(
		uintptr(unsafe.Pointer(&envBlock)),
		uintptr(token),
		0,
	)
	if ret == 0 {
		return nil, nil, err
	}
	cleanup := func() {
		procDestroyEnvironmentBlock.Call(envBlock)
	}
	return envBlockToStrings(envBlock), cleanup, nil
}

func envBlockToStrings(block uintptr) []string {
	if block == 0 {
		return nil
	}
	result := make([]string, 0)
	offset := 0
	for {
		start := offset
		for {
			ch := *(*uint16)(unsafe.Pointer(block + uintptr(offset)*2))
			if ch == 0 {
				break
			}
			offset++
		}
		if offset == start {
			break
		}
		chars := unsafe.Slice((*uint16)(unsafe.Pointer(block+uintptr(start)*2)), offset-start)
		result = append(result, syscall.UTF16ToString(chars))
		offset++
	}
	return result
}

func buildEnvBlock(env []string) []uint16 {
	if len(env) == 0 {
		return nil
	}
	env = normalizeEnv(env)
	joined := strings.Join(env, "\x00") + "\x00\x00"
	return utf16.Encode([]rune(joined))
}

func mergeEnv(base []string, extra map[string]string, allowIdentityOverride bool) []string {
	result := append([]string(nil), base...)
	if len(extra) == 0 {
		return normalizeEnv(result)
	}
	index := make(map[string]int, len(result))
	for i, env := range result {
		key, ok := splitEnvKey(env)
		if ok {
			index[strings.ToUpper(key)] = i
		}
	}

	keys := make([]string, 0, len(extra))
	for key := range extra {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	for _, key := range keys {
		if !allowIdentityOverride && isIdentityEnvKey(key) {
			continue
		}
		item := key + "=" + extra[key]
		upper := strings.ToUpper(key)
		if i, ok := index[upper]; ok {
			result[i] = item
		} else {
			index[upper] = len(result)
			result = append(result, item)
		}
	}
	return normalizeEnv(result)
}

func normalizeEnv(env []string) []string {
	result := append([]string(nil), env...)
	sort.SliceStable(result, func(i, j int) bool {
		ki, _ := splitEnvKey(result[i])
		kj, _ := splitEnvKey(result[j])
		return strings.ToUpper(ki) < strings.ToUpper(kj)
	})
	return result
}

func splitEnvKey(env string) (string, bool) {
	if env == "" {
		return "", false
	}
	start := 0
	if env[0] == '=' {
		start = 1
	}
	idx := strings.Index(env[start:], "=")
	if idx < 0 {
		return "", false
	}
	return env[:start+idx], true
}

func isIdentityEnvKey(key string) bool {
	_, ok := identityEnvKeys[strings.ToUpper(key)]
	return ok
}
