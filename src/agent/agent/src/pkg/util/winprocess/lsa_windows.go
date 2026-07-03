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
	"fmt"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	procLsaOpenPolicy          = modadvapi32.NewProc("LsaOpenPolicy")
	procLsaRetrievePrivateData = modadvapi32.NewProc("LsaRetrievePrivateData")
	procLsaFreeMemory          = modadvapi32.NewProc("LsaFreeMemory")
	procLsaClose               = modadvapi32.NewProc("LsaClose")
)

const (
	policyGetPrivateInformation uint32 = 0x00000004

	lsaSecretKeyUser     = "BkCiSessionUser"
	lsaSecretKeyPassword = "BkCiSessionPassword"
)

type lsaUnicodeString struct {
	Length        uint16
	MaximumLength uint16
	Buffer        *uint16
}

type lsaObjectAttributes struct {
	Length                   uint32
	RootDirectory            uintptr
	ObjectName               uintptr
	Attributes               uint32
	SecurityDescriptor       uintptr
	SecurityQualityOfService uintptr
}

func ReadLsaSecret(keyName string) (string, error) {
	var attrs lsaObjectAttributes
	attrs.Length = uint32(unsafe.Sizeof(attrs))
	var systemName lsaUnicodeString
	var policyHandle uintptr

	ret, _, err := procLsaOpenPolicy.Call(
		uintptr(unsafe.Pointer(&systemName)),
		uintptr(unsafe.Pointer(&attrs)),
		uintptr(policyGetPrivateInformation),
		uintptr(unsafe.Pointer(&policyHandle)),
	)
	if ret != 0 {
		return "", fmt.Errorf("LsaOpenPolicy: NTSTATUS 0x%x: %w", ret, err)
	}
	defer procLsaClose.Call(policyHandle)

	key := lsaUnicodeString{
		Length:        uint16(len(keyName) * 2),
		MaximumLength: uint16((len(keyName) + 1) * 2),
		Buffer:        windows.StringToUTF16Ptr(keyName),
	}

	var privateData *lsaUnicodeString
	ret, _, err = procLsaRetrievePrivateData.Call(
		policyHandle,
		uintptr(unsafe.Pointer(&key)),
		uintptr(unsafe.Pointer(&privateData)),
	)
	if ret != 0 {
		return "", fmt.Errorf("LsaRetrievePrivateData(%s): NTSTATUS 0x%x: %w", keyName, ret, err)
	}
	if privateData == nil || privateData.Buffer == nil {
		return "", fmt.Errorf("LsaRetrievePrivateData(%s): empty result", keyName)
	}
	defer procLsaFreeMemory.Call(uintptr(unsafe.Pointer(privateData)))

	return windows.UTF16PtrToString(privateData.Buffer), nil
}

func ReadSessionCredentials() (user, password string) {
	u, err := ReadLsaSecret(lsaSecretKeyUser)
	if err != nil {
		return "", ""
	}
	p, err := ReadLsaSecret(lsaSecretKeyPassword)
	if err != nil {
		return "", ""
	}
	return u, p
}

func SplitUserDomain(account string) (user, domain string) {
	if account == "" {
		return "", "."
	}
	for i := 0; i < len(account); i++ {
		if account[i] == '\\' {
			u := account[i+1:]
			if u == "" {
				return account, "."
			}
			return u, account[:i]
		}
	}
	for i := 0; i < len(account); i++ {
		if account[i] == '@' {
			return account[:i], account[i+1:]
		}
	}
	return account, "."
}
