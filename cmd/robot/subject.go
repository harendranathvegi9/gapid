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
	"flag"
	"strings"
	"time"

	"github.com/golang/protobuf/proto"
	"github.com/golang/protobuf/ptypes"
	"github.com/google/gapid/core/app"
	"github.com/google/gapid/core/data/search/script"
	"github.com/google/gapid/core/fault/cause"
	"github.com/google/gapid/core/log"
	"github.com/google/gapid/core/net/grpcutil"
	"github.com/google/gapid/test/robot/subject"
	"google.golang.org/grpc"
)

var subjectTraceTime = 0 * time.Second

func init() {
	subjectUpload := &app.Verb{
		Name:       "subject",
		ShortHelp:  "Upload a traceable application to the server",
		ShortUsage: "<filenames>",
		Run:        doUpload(&subjectUploader{}),
	}
	subjectUpload.Flags.Raw.DurationVar(&subjectTraceTime, "tracetime", subjectTraceTime, "trace time override (if non-zero)")
	uploadVerb.Add(subjectUpload)
	subjectSearch := &app.Verb{
		Name:       "subject",
		ShortHelp:  "List traceable applications in the server",
		ShortUsage: "<query>",
		Run:        doSubjectSearch,
	}
	searchVerb.Add(subjectSearch)
}

type subjectUploader struct {
	subjects subject.Subjects
}

func (u *subjectUploader) prepare(ctx log.Context, conn *grpc.ClientConn) error {
	u.subjects = subject.NewRemote(ctx, conn)
	return nil
}

func (u *subjectUploader) process(ctx log.Context, id string) error {
	var hints *subject.Hints
	if subjectTraceTime != 0 {
		hints = &subject.Hints{TraceTime: ptypes.DurationProto(subjectTraceTime)}
	}
	subject, created, err := u.subjects.Add(ctx, id, hints)
	if err != nil {
		return cause.Explain(ctx, err, "Failed processing subject")
	}
	out := ctx.Raw("")
	if created {
		out.Logf("Added new subject")
	} else {
		out.Logf("Already existing subject")
	}

	out.Logf("Subject info %s", subject)
	return nil
}

func doSubjectSearch(ctx log.Context, flags flag.FlagSet) error {
	return grpcutil.Client(ctx, serverAddress, func(ctx log.Context, conn *grpc.ClientConn) error {
		subjects := subject.NewRemote(ctx, conn)
		expression := strings.Join(flags.Args(), " ")
		out := ctx.Raw("").Writer()
		expr, err := script.Parse(ctx, expression)
		if err != nil {
			return cause.Explain(ctx, err, "Malformed search query")
		}
		return subjects.Search(ctx, expr.Query(), func(ctx log.Context, entry *subject.Subject) error {
			proto.MarshalText(out, entry)
			return nil
		})
	}, grpc.WithInsecure())
}
