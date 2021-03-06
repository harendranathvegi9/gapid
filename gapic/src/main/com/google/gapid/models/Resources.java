/*
 * Copyright (C) 2017 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.gapid.models;

import com.google.gapid.proto.service.Service;
import com.google.gapid.proto.service.Service.ResourcesByType;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.server.Client;
import com.google.gapid.util.Events;

import org.eclipse.swt.widgets.Shell;

import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

public class Resources extends CaptureDependentModel<Service.Resources> {
  private static final Logger LOG = Logger.getLogger(Resources.class.getName());

  private final Events.ListenerCollection<Listener> listeners = Events.listeners(Listener.class);

  public Resources(Shell shell, Client client, Capture capture) {
    super(LOG, shell, client, capture);
  }

  @Override
  protected Path.Any getPath(Path.Capture capturePath) {
    return Path.Any.newBuilder()
        .setResources(Path.Resources.newBuilder()
            .setCapture(capturePath))
        .build();
  }

  @Override
  protected Service.Resources unbox(Service.Value value) throws IOException {
    return value.getResources();
  }

  @Override
  protected void fireLoadEvent() {
    listeners.fire().onResourcesLoaded();
  }

  public List<ResourcesByType> getResources() {
    return getData().getTypesList();
  }

  public void addListener(Listener listener) {
    listeners.addListener(listener);
  }

  public void removeListener(Listener listener) {
    listeners.removeListener(listener);
  }

  public static interface Listener extends Events.Listener {
    public default void onResourcesLoaded() { /* empty */ }
  }
}
