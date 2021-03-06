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
package com.google.gapid.views;

import static com.google.gapid.util.Loadable.MessageType.Error;
import static com.google.gapid.util.Loadable.MessageType.Info;
import static com.google.gapid.util.Paths.resourceAfter;
import static com.google.gapid.util.Ranges.last;
import static com.google.gapid.widgets.Widgets.createStandardTabFolder;
import static com.google.gapid.widgets.Widgets.createStandardTabItem;
import static com.google.gapid.widgets.Widgets.scheduleIfNotDisposed;

import com.google.common.collect.Lists;
import com.google.gapid.Server.GapisInitException;
import com.google.gapid.lang.glsl.GlslSourceConfiguration;
import com.google.gapid.models.AtomStream;
import com.google.gapid.models.Capture;
import com.google.gapid.models.Models;
import com.google.gapid.models.Resources;
import com.google.gapid.proto.service.Service;
import com.google.gapid.proto.service.Service.CommandRange;
import com.google.gapid.proto.service.gfxapi.GfxAPI.Program;
import com.google.gapid.proto.service.gfxapi.GfxAPI.ResourceType;
import com.google.gapid.proto.service.gfxapi.GfxAPI.Shader;
import com.google.gapid.proto.service.gfxapi.GfxAPI.Uniform;
import com.google.gapid.proto.service.path.Path;
import com.google.gapid.proto.service.pod.Pod;
import com.google.gapid.rpclib.futures.FutureController;
import com.google.gapid.rpclib.futures.SingleInFlight;
import com.google.gapid.rpclib.rpccore.Rpc;
import com.google.gapid.rpclib.rpccore.Rpc.Result;
import com.google.gapid.rpclib.rpccore.RpcException;
import com.google.gapid.server.Client;
import com.google.gapid.util.Messages;
import com.google.gapid.util.ProtoDebugTextFormat;
import com.google.gapid.util.UiCallback;
import com.google.gapid.widgets.LoadablePanel;
import com.google.gapid.widgets.Theme;
import com.google.gapid.widgets.Widgets;

import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.ComboViewer;
import org.eclipse.jface.viewers.LabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TableColumn;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

public class ShaderView extends Composite implements Capture.Listener, AtomStream.Listener, Resources.Listener {
  protected static final Logger LOG = Logger.getLogger(ShaderView.class.getName());
  protected static final String EMPTY_SHADER =
      "// No source attached to this shader at this point in the trace.";
  protected static final String EMPTY_PROGRAM =
      "// No shaders attached to this program at this point in the trace.";
  protected static final char[] SHADER_SEPARATOR;
  static {
    SHADER_SEPARATOR = new char[81];
    SHADER_SEPARATOR[0] = SHADER_SEPARATOR[1] = '/';
    SHADER_SEPARATOR[2] = ' ';
    Arrays.fill(SHADER_SEPARATOR, 3, SHADER_SEPARATOR.length - 1, '=');
    SHADER_SEPARATOR[SHADER_SEPARATOR.length - 1] = '\n';
  }

  private final Client client;
  protected final Models models;
  private final FutureController shaderRpcController = new SingleInFlight();
  private final FutureController programRpcController = new SingleInFlight();
  private final LoadablePanel<TabFolder> loading;

  public ShaderView(Composite parent, Client client, Models models, Widgets widgets) {
    super(parent, SWT.NONE);
    this.client = client;
    this.models = models;

    setLayout(new FillLayout());

    loading = LoadablePanel.create(this, widgets, panel -> createStandardTabFolder(panel));
    TabFolder folder = loading.getContents();
    createStandardTabItem(folder, "Shaders", createShaderTab(folder, widgets));
    createStandardTabItem(folder, "Programs", createProgramTab(folder, widgets));

    models.capture.addListener(this);
    models.atoms.addListener(this);
    models.resources.addListener(this);
  }

  private Control createShaderTab(Composite parent, Widgets widgets) {
    ShaderPanel panel = new ShaderPanel(parent, models, widgets.theme, Type.shader((data, src) -> {
      Shader shader = (data == null) ? null : (Shader)data.resource;
      if (shader != null) {
        Service.Value value = Service.Value.newBuilder()
            .setShader(shader.toBuilder()
                .setSource(src))
            .build();
        Rpc.listen(client.set(data.path, value), new UiCallback<Path.Any, Path.Capture>(this, LOG) {
          @Override
          protected Path.Capture onRpcThread(Rpc.Result<Path.Any> result)
              throws RpcException, ExecutionException {
            // TODO this should probably be able to handle any path.
            return result.get().getResourceData().getAfter().getCommands().getCapture();
          }

          @Override
          protected void onUiThread(Path.Capture result) {
            models.capture.updateCapture(result, null);
          }
        });
      }
    }));
    panel.addListener(SWT.Selection, e -> getShaderSource((Data)e.data, panel::setSource));
    return panel;
  }

  private Control createProgramTab(Composite parent, Widgets widgets) {
    SashForm splitter = new SashForm(parent, SWT.VERTICAL);

    ShaderPanel panel = new ShaderPanel(splitter, models, widgets.theme, Type.program());
    Composite uniformsGroup = Widgets.createGroup(splitter, "Uniforms");
    uniformsGroup.setLayout(new FillLayout(SWT.VERTICAL));
    UniformsPanel uniforms = new UniformsPanel(uniformsGroup);

    splitter.setWeights(models.settings.shaderSplitterWeights);

    panel.addListener(SWT.Selection, e -> getProgramSource((Data)e.data,
        program -> scheduleIfNotDisposed(uniforms, () -> uniforms.setUniforms(program)),
        panel::setSource));
    addListener(SWT.Dispose, e -> models.settings.shaderSplitterWeights = splitter.getWeights());
    return splitter;
  }

  private void getShaderSource(Data data, Consumer<String> callback) {
    Rpc.listen(client.get(data.path), shaderRpcController,
        new UiCallback<Service.Value, String>(this, LOG) {
      @Override
      protected String onRpcThread(Result<Service.Value> result)
          throws RpcException, ExecutionException {
        Shader shader = result.get().getShader();
        data.resource = shader;
        String source = shader.getSource();
        return source.isEmpty() ? EMPTY_SHADER : source;
      }

      @Override
      protected void onUiThread(String result) {
        callback.accept(result);
      }
    });
  }

  private void getProgramSource(
      Data data, Consumer<Program> onProgramLoaded, Consumer<String> callback) {
    Rpc.listen(client.get(data.path), programRpcController,
        new UiCallback<Service.Value, String>(this, LOG) {
      @Override
      protected String onRpcThread(Result<Service.Value> result)
          throws RpcException, ExecutionException {
        Program program = result.get().getProgram();
        data.resource = program;
        onProgramLoaded.accept(program);
        StringBuilder sb = new StringBuilder();
        for (Shader shader : program.getShadersList()) {
          sb.append(SHADER_SEPARATOR);
          sb.append("// " + shader.getType() + " Shader\n");
          sb.append(SHADER_SEPARATOR);
          sb.append(shader.getSource());
          if (sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
          }
          sb.append("\n\n");
        }
        return (sb.length() == 0) ? EMPTY_PROGRAM : sb.toString();
      }

      @Override
      protected void onUiThread(String result) {
        callback.accept(result);
      }
    });
  }

  @Override
  public void onCaptureLoadingStart() {
    loading.showMessage(Info, Messages.LOADING_CAPTURE);
  }

  @Override
  public void onCaptureLoaded(GapisInitException error) {
    if (error != null) {
      loading.showMessage(Error, Messages.CAPTURE_LOAD_FAILURE);
    }
  }

  @Override
  public void onAtomsLoaded() {
    if (!models.atoms.isLoaded()) {
      loading.showMessage(Info, Messages.CAPTURE_LOAD_FAILURE);
    } else {
      updateLoading();
    }
  }

  @Override
  public void onAtomsSelected(CommandRange path) {
    updateLoading();
  }

  @Override
  public void onResourcesLoaded() {
    if (!models.resources.isLoaded()) {
      loading.showMessage(Info, Messages.CAPTURE_LOAD_FAILURE);
    } else {
      updateLoading();
    }
  }

  private void updateLoading() {
    if (models.atoms.isLoaded() && models.resources.isLoaded()) {
      if (models.atoms.getSelectedAtoms() == null) {
        loading.showMessage(Info, Messages.SELECT_ATOM);
      } else {
        loading.stopLoading();
      }
    }
  }

  private static class ShaderPanel extends Composite
      implements Resources.Listener, AtomStream.Listener {
    private final Models models;
    protected final Type type;
    private final ComboViewer shaderCombo;
    private final SourceViewer sourcePanel;
    private final Button pushButton;

    public ShaderPanel(Composite parent, Models models, Theme theme, Type type) {
      super(parent, SWT.NONE);
      this.models = models;
      this.type = type;

      setLayout(new GridLayout(1, false));
      shaderCombo = createShaderSelector();
      sourcePanel = createSourcePanel(!type.isEditable(), theme);

      shaderCombo.getCombo().setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
      sourcePanel.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

      if (type.isEditable()) {
        pushButton = Widgets.createButton(this, "Push Changes",
            e -> type.updateShader(
                (Data)shaderCombo.getElementAt(shaderCombo.getCombo().getSelectionIndex()),
                sourcePanel.getDocument().get()));
        pushButton.setLayoutData(new GridData(SWT.RIGHT, SWT.BOTTOM, false, false));
        pushButton.setEnabled(false);
      } else {
        pushButton = null;
      }

      models.resources.addListener(this);
      models.atoms.addListener(this);

      shaderCombo.getCombo().addListener(SWT.Selection, e -> updateSelection());
    }

    private ComboViewer createShaderSelector() {
      ComboViewer combo = new ComboViewer(this, SWT.READ_ONLY);
      combo.setContentProvider(ArrayContentProvider.getInstance());
      combo.setLabelProvider(new LabelProvider());
      combo.setUseHashlookup(true);
      combo.getCombo().setVisibleItemCount(10);
      return combo;
    }

    private SourceViewer createSourcePanel(boolean readOnly, Theme theme) {
      SourceViewer viewer = new SourceViewer(this, null, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
      viewer.setEditable(!readOnly);
      viewer.getTextWidget().setFont(JFaceResources.getFont(JFaceResources.TEXT_FONT));
      viewer.configure(new GlslSourceConfiguration(theme));
      return viewer;
    }

    public void clearSource() {
      sourcePanel.setDocument(new Document());
      if (pushButton != null) {
        pushButton.setEnabled(false);
      }
    }

    public void setSource(String source) {
      sourcePanel.setDocument(GlslSourceConfiguration.createDocument(source));
      if (pushButton != null) {
        pushButton.setEnabled(true);
      }
    }

    @Override
    public void onResourcesLoaded() {
      updateShaders();
    }

    @Override
    public void onAtomsSelected(CommandRange path) {
      updateShaders();
    }

    private void updateShaders() {
      if (models.resources.isLoaded() && models.atoms.getSelectedAtoms() != null) {
        List<Data> shaders = Lists.newArrayList();
        CommandRange range = models.atoms.getSelectedAtoms();
        for (Service.ResourcesByType bundle : models.resources.getResources()) {
          if (bundle.getType() == type.type) {
            for (Service.Resource info : bundle.getResourcesList()) {
              if (firstAccess(info) <= last(range)) {
                if (shaders.isEmpty()) {
                  shaders.add(new Data(null, null) {
                    @Override
                    public String toString() {
                      return type.selectMessage;
                    }
                  });
                }
                shaders.add(
                    new Data(resourceAfter(models.atoms.getPath(), range, info.getId()), info));
              }
            }
          }
        }

        int selection = shaderCombo.getCombo().getSelectionIndex();
        shaderCombo.setInput(shaders);
        shaderCombo.refresh();

        if (selection >= 0 && selection < shaders.size()) {
          shaderCombo.getCombo().select(selection);
        } else if (!shaders.isEmpty()) {
          shaderCombo.getCombo().select(0);
        }
      } else {
        shaderCombo.setInput(Collections.emptyList());
        shaderCombo.refresh();
      }
      updateSelection();
    }

    private static long firstAccess(Service.Resource info) {
      return (info.getAccessesCount() == 0) ? 0 : info.getAccesses(0);
    }

    private void updateSelection() {
      int index = shaderCombo.getCombo().getSelectionIndex();
      if (index < 0) {
        clearSource();
      } else if (index == 0) {
        // Ignore the null item selection.
      } else {
        Event event = new Event();
        event.data = shaderCombo.getElementAt(index);
        notifyListeners(SWT.Selection, event);
      }
    }

    protected static interface UpdateShader {
      public void updateShader(Data data, String newSource);
    }
  }

  private static class UniformsPanel extends Composite {
    private final TableViewer table;

    public UniformsPanel(Composite parent) {
      super(parent, SWT.NONE);
      setLayout(new FillLayout(SWT.VERTICAL));

      table = new TableViewer(this, SWT.BORDER | SWT.SINGLE | SWT.H_SCROLL | SWT.V_SCROLL);
      table.getTable().setHeaderVisible(true);
      table.getTable().setLinesVisible(true);
      table.setContentProvider(new ArrayContentProvider());

      createColumn(table, "Location", uniform -> String.valueOf(uniform.getUniformLocation()));
      createColumn(table, "Name", Uniform::getName);
      createColumn(table, "Type", uniform -> String.valueOf(uniform.getType()));
      createColumn(table, "Format", uniform -> String.valueOf(uniform.getFormat()));
      createColumn(table, "Value", uniform -> {
        Pod.Value value = uniform.getValue();
        switch (uniform.getType()) {
          case Int32: return String.valueOf(value.getSint32Array().getValList());
          case Uint32: return String.valueOf(value.getUint32Array().getValList());
          case Bool: return String.valueOf(value.getBoolArray().getValList());
          case Float: return String.valueOf(value.getFloatArray().getValList());
          case Double: return String.valueOf(value.getDoubleArray().getValList());
          default: return ProtoDebugTextFormat.shortDebugString(value);
        }
      });
      updateColumnSizes();
    }

    public void setUniforms(Program program) {
      List<Uniform> uniforms = Lists.newArrayList(program.getUniformsList());
      Collections.sort(uniforms, (a, b) -> a.getUniformLocation() - b.getUniformLocation());
      table.setInput(uniforms);
      table.refresh();
      updateColumnSizes();
      table.getTable().requestLayout();
    }

    private void updateColumnSizes() {
      for (TableColumn column : table.getTable().getColumns()) {
        column.pack();
      }
    }

    private static TableViewerColumn createColumn(
        TableViewer table, String title, Function<Uniform, String> labelProvider) {
      TableViewerColumn column = Widgets.createTableColum(table, title);
      column.setLabelProvider(new ColumnLabelProvider() {
        @Override
        public String getText(Object element) {
          return labelProvider.apply((Uniform)element);
        }
      });
      return column;
    }
  }

  private static class Type implements ShaderPanel.UpdateShader {
    public final ResourceType type;
    public final String selectMessage;
    public final ShaderPanel.UpdateShader onSourceEdited;

    public Type(ResourceType type, String selectMessage, ShaderPanel.UpdateShader onSourceEdited) {
      this.type = type;
      this.selectMessage = selectMessage;
      this.onSourceEdited = onSourceEdited;
    }

    public static Type shader(ShaderPanel.UpdateShader onSourceEdited) {
      return new Type(ResourceType.ShaderResource, Messages.SELECT_SHADER, onSourceEdited);
    }

    public static Type program() {
      return new Type(ResourceType.ProgramResource, Messages.SELECT_PROGRAM, null);
    }

    @Override
    public void updateShader(Data data, String newSource) {
      onSourceEdited.updateShader(data, newSource);
    }

    public boolean isEditable() {
      return onSourceEdited != null;
    }
  }

  private static class Data {
    public final Path.Any path;
    public final Service.Resource info;
    public Object resource;

    public Data(Path.Any path, Service.Resource info) {
      this.path = path;
      this.info = info;
    }

    @Override
    public String toString() {
      return info.getName();
    }
  }
}
