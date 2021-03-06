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

package subject

import (
	"github.com/google/gapid/core/data/search"
	"github.com/google/gapid/core/event"
	"github.com/google/gapid/core/log"
	"github.com/google/gapid/core/net/grpcutil"
	"google.golang.org/grpc"
)

type remote struct {
	client ServiceClient
}

// NewRemote returns a Subjects that talks to a remote grpc Subject service.
func NewRemote(ctx log.Context, conn *grpc.ClientConn) Subjects {
	return &remote{
		client: NewServiceClient(conn),
	}
}

// Search implements Subjects.Search
// It forwards the call through grpc to the remote implementation.
func (m *remote) Search(ctx log.Context, query *search.Query, handler Handler) error {
	stream, err := m.client.Search(ctx.Unwrap(), query)
	if err != nil {
		return err
	}
	return event.Feed(ctx, event.AsHandler(ctx, handler), grpcutil.ToProducer(stream))
}

// Add implements Subjects.Add
// It forwards the call through grpc to the remote implementation.
func (m *remote) Add(ctx log.Context, id string, hints *Hints) (*Subject, bool, error) {
	request := &AddRequest{Id: id, Hints: hints}
	response, err := m.client.Add(ctx.Unwrap(), request)
	if err != nil {
		return nil, false, err
	}
	return response.Subject, response.Created, nil
}
