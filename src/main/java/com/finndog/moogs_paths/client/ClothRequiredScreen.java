package com.finndog.moogs_paths.client;

//? if <1.20.6 {
/*import net.minecraft.client.gui.GuiGraphics;
*///?}
import net.minecraft.client.gui.components.Button;
//? if >=1.20.6 {
import net.minecraft.client.gui.components.MultiLineTextWidget;
//?}
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
//? if <1.20.6 {
/*import net.minecraft.util.FormattedCharSequence;
*///?}

/**
 * Shown by the mod-list config button when Cloth Config isn't installed: a plain vanilla screen saying
 * the sliders need Cloth Config and where the file is, so the button never does nothing.
 */
public class ClothRequiredScreen extends Screen {
    private static final Component MESSAGE = Component.translatable("moogs_paths.cloth_required.message");
    //? if <1.20.6 {
    /*private static final int MAX_WIDTH = 310;
    *///?}

    private final Screen parent;

    public ClothRequiredScreen(Screen parent) {
        super(Component.translatable("moogs_paths.cloth_required.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        //? if >=1.20.6 {
        MultiLineTextWidget text = new MultiLineTextWidget(cx - 155, this.height / 2 - 40, MESSAGE, this.font);
        text.setMaxWidth(310);
        text.setCentered(true);
        addRenderableWidget(text);
        //?}
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
            .bounds(cx - 100, this.height / 2 + 30, 200, 20).build());
    //? if <1.20.6 {
    /*}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.renderBackground(graphics);
        int y = this.height / 2 - 40;
        for(FormattedCharSequence line : this.font.split(MESSAGE, MAX_WIDTH)) {
            graphics.drawCenteredString(this.font, line, this.width / 2, y, 0xFFFFFF);
            y += this.font.lineHeight;
        }
        super.render(graphics, mouseX, mouseY, delta);
    *///?}
    }

    @Override
    public void onClose() {
        //? if <26.2 {
        this.minecraft.setScreen(this.parent);
        //?} else {
        /*this.minecraft.setScreenAndShow(this.parent);
        *///?}
    }
}
