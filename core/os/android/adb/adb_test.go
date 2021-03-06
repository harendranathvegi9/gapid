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

package adb_test

import (
	"testing"

	"github.com/google/gapid/core/assert"
	"github.com/google/gapid/core/context/jot"
	"github.com/google/gapid/core/log"
	"github.com/google/gapid/core/os/android/adb"
)

func mustConnect(ctx log.Context, serial string) adb.Device {
	devices, err := adb.Devices(ctx)
	if err != nil {
		jot.Fail(ctx, err, "Couldn't get devices")
	}
	for _, d := range devices {
		if d.Instance().Serial == serial {
			return d
		}
	}
	jot.Fatalf(ctx, nil, "Couldn't find device '%v'", serial)
	return nil
}

func TestADBShell(t_ *testing.T) {
	ctx := log.Testing(t_)
	d := mustConnect(ctx, "production_device")
	assert.For(ctx, "Device").ThatString(d).Equals("production_device")
	assert.For(ctx, "Device shell").ThatString(d.Shell("").Target).Equals("shell:production_device")
}
