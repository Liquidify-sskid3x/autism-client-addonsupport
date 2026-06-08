package net.minecraft.client.renderer.rendertype;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.resources.Identifier;

import java.util.Optional;

public final class AutismRenderTypes {
    private static final RenderPipeline STORAGE_ESP_FILL_PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("autismclient", "pipeline/storage_esp_fill_see_through"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withVertexShader("core/position_color")
        .withFragmentShader("core/position_color")
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS)
        .withDepthStencilState(Optional.empty())
        .build();

    private static final RenderPipeline STORAGE_ESP_LINES_PIPELINE = RenderPipeline.builder()
        .withLocation(Identifier.fromNamespaceAndPath("autismclient", "pipeline/storage_esp_lines_see_through"))
        .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
        .withUniform("Projection", UniformType.UNIFORM_BUFFER)
        .withUniform("Fog", UniformType.UNIFORM_BUFFER)
        .withUniform("Globals", UniformType.UNIFORM_BUFFER)
        .withVertexShader("core/rendertype_lines")
        .withFragmentShader("core/rendertype_lines")
        .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
        .withCull(false)
        .withVertexFormat(DefaultVertexFormat.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.Mode.LINES)
        .withDepthStencilState(Optional.empty())
        .build();

    private static final RenderType STORAGE_ESP_FILL = RenderType.create(
        "autism_storage_esp_fill_see_through",
        RenderSetup.builder(STORAGE_ESP_FILL_PIPELINE).sortOnUpload().createRenderSetup()
    );

    private static final RenderType STORAGE_ESP_LINES = RenderType.create(
        "autism_storage_esp_lines_see_through",
        RenderSetup.builder(STORAGE_ESP_LINES_PIPELINE).createRenderSetup()
    );

    private AutismRenderTypes() {
    }

    public static RenderType storageEspFillSeeThrough() {
        return STORAGE_ESP_FILL;
    }

    public static RenderType storageEspLinesSeeThrough() {
        return STORAGE_ESP_LINES;
    }
}
