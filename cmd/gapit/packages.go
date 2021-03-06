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

package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"github.com/golang/protobuf/proto"
	"github.com/google/gapid/core/app"
	"github.com/google/gapid/core/fault/cause"
	"github.com/google/gapid/core/log"
	"github.com/google/gapid/gapidapk"
)

type packagesVerb struct{ PackagesFlags }

func init() {
	verb := &packagesVerb{
		PackagesFlags{
			Icons:       false,
			IconDensity: 1.0,
		},
	}
	app.AddVerb(&app.Verb{
		Name:      "packages",
		ShortHelp: "Prints information about packages installed on a device",
		Auto:      verb,
	})
}

func (verb *packagesVerb) Run(ctx log.Context, flags flag.FlagSet) error {
	d, err := getADBDevice(ctx, verb.Device)
	if err != nil {
		return err
	}

	pkgs, err := gapidapk.PackageList(ctx, d, verb.Icons, float32(verb.IconDensity))
	if err != nil {
		return cause.Explain(ctx, err, "getting package list")
	}

	w := os.Stdout
	if verb.Out != "" {
		f, err := os.OpenFile(verb.Out, os.O_RDWR|os.O_CREATE|os.O_TRUNC, 0644)
		if err != nil {
			return cause.Explain(ctx, err, "Failed to open package list output file")
		}
		w = f
		defer w.Close()
	}

	switch verb.Format {
	case ProtoString:
		fmt.Fprintf(w, pkgs.String())

	case Proto:
		data, err := proto.Marshal(pkgs)
		if err != nil {
			return cause.Explain(ctx, err, "marshal protobuf")
		}
		w.Write(data)

	case Json:
		e := json.NewEncoder(w)
		e.SetIndent("", "  ")
		if err := e.Encode(pkgs); err != nil {
			return cause.Explain(ctx, err, "marshal json")
		}

	case SimpleList:
		for _, a := range pkgs.GetPackages() {
			fmt.Fprintf(w, "%s\n", a.Name)
		}
	}

	return nil
}
