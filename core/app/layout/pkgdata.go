// Copyright (C) 2017 Google Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package layout

import (
	"github.com/google/gapid/core/log"
	"github.com/google/gapid/core/os/device"
	"github.com/google/gapid/core/os/file"
)

// resolvedLayout is the data file layout discovered by layout()
// Call layout() instead of using this directly.
var resolvedLayout FileLayout

func layout() (out FileLayout) {
	if resolvedLayout != nil {
		return resolvedLayout
	}
	defer func() { resolvedLayout = out }()
	for _, base := range []file.Path{file.ExecutablePath(), file.Abs(".")} {
		dir := base.Parent()
		// Check the regular package layout first:
		// pkg
		//  ├─── source.properties
		//  ├─── strings
		//  │     └─── en-us.stb
		//  ├─── android
		//  │     ├─── arm64-v8a
		//  │     │     └─── gapid.apk
		//  │     ├─── armeabi-v7a
		//  │     │     └─── gapid.apk
		//  │     └─── x86
		//  │           └─── gapid.apk
		//  ├─── osx
		//  │     └─── x86_64
		//  │           ├─── gapir
		//  │           ├─── gapis
		//  │           ↓
		//  ├─── linux
		//  │    ↓
		//  ↓
		if root := dir.Parent().Parent(); root.Join("source.properties").Exists() {
			return pkgLayout{root}
		}
		// Check bin layout from executable's directory.
		// bin
		//  ├─── android-armv7a
		//  │     └─── gapid.apk
		//  ├─── android-armv8a
		//  │     └─── gapid.apk
		//  ├─── android-x86
		//  │     └─── gapid.apk
		//  ├─── strings
		//  │     └─── en-us.stb
		//  ├─── gapir
		//  ├─── gapis
		//  ↓
		for _, abiDirName := range binABIToDir {
			if dir.Join(abiDirName).Exists() {
				return binLayout{dir}
			}
		}
	}
	return unknownLayout{}
}

// File returns the path to the specified file for the given ABI.
func File(ctx log.Context, abi *device.ABI, name string) (file.Path, error) {
	return layout().File(ctx, abi, name)
}

// Strings returns the path to the binary string table.
func Strings(ctx log.Context) (file.Path, error) {
	return layout().Strings(ctx)
}

// GapidApk returns the path to the gapid.apk corresponding to the given abi.
func GapidApk(ctx log.Context, abi *device.ABI) (file.Path, error) {
	return layout().GapidApk(ctx, abi)
}
