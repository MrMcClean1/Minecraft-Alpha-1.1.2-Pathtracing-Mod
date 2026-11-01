package com.alexmcclellan.betapt.bots;

import net.minecraft.block.Block;
import net.minecraft.client.render.vertex.Tesselator;
import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.block.BlockRenderer;
import net.minecraft.client.render.entity.MobRenderer;
import net.minecraft.client.render.model.entity.HumanoidModel;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import org.lwjgl.opengl.GL11;

/** A bot looks like a player on Alpha too: the default skin, the tool in its hand, its name over its head. */
public class BotRenderer extends MobRenderer<BotEntity> {
    private final HumanoidModel body;

    public BotRenderer() { super(new HumanoidModel(0.0F), 0.5F); body = (HumanoidModel) this.model; }

    @Override
    public void render(BotEntity e, double dx, double dy, double dz, float yaw, float tickDelta) {
        body.itemInRightHand = e.heldStack != null;
        super.render(e, dx, dy, dz, yaw, tickDelta);
        body.itemInRightHand = false;
        TextRenderer tr = this.getTextRenderer();
        String s = e.botName();
        float g = 0.016666668F * 1.6F * (float) (Math.sqrt(e.distanceTo(this.dispatcher.camera)) / 2.0);
        GL11.glPushMatrix();
        GL11.glTranslatef((float) dx, (float) dy + 2.3F, (float) dz);
        GL11.glNormal3f(0.0F, 1.0F, 0.0F);
        GL11.glRotatef(-this.dispatcher.cameraYaw, 0.0F, 1.0F, 0.0F);
        GL11.glRotatef(this.dispatcher.cameraPitch, 1.0F, 0.0F, 0.0F);
        GL11.glScalef(-g, -g, g);
        GL11.glDisable(2896); GL11.glDepthMask(false); GL11.glDisable(2929); GL11.glEnable(3042); GL11.glBlendFunc(770, 771);
        Tesselator t = Tesselator.INSTANCE;
        GL11.glDisable(3553);
        t.begin();
        int i = tr.getWidth(s) / 2;
        t.color(0.0F, 0.0F, 0.0F, 0.25F);
        t.vertex(-i - 1, -1.0, 0.0); t.vertex(-i - 1, 8.0, 0.0); t.vertex(i + 1, 8.0, 0.0); t.vertex(i + 1, -1.0, 0.0);
        t.end();
        GL11.glEnable(3553);
        tr.draw(s, -i, 0, 553648127);
        GL11.glEnable(2929); GL11.glDepthMask(true);
        tr.draw(s, -i, 0, -1);
        GL11.glEnable(2896); GL11.glDisable(3042); GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    @Override
    protected void renderMore(BotEntity e, float tickDelta) {
        ItemStack it = e.heldStack;
        if (it == null) return;
        GL11.glPushMatrix();
        body.rightArm.transform(0.0625F);
        GL11.glTranslatef(-0.0625F, 0.4375F, 0.0625F);
        if (it.id < 256 && BlockRenderer.isItem3d(Block.BY_ID[it.id].getRenderType())) {
            float f = 0.375F;
            GL11.glTranslatef(0.0F, 0.1875F, -0.3125F);
            GL11.glRotatef(20.0F, 1.0F, 0.0F, 0.0F); GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F); GL11.glScalef(f, -f, f);
        } else if (Item.BY_ID[it.id] != null && Item.BY_ID[it.id].isWielded()) {
            float g = 0.625F;
            GL11.glTranslatef(0.0F, 0.1875F, 0.0F); GL11.glScalef(g, -g, g);
            GL11.glRotatef(-100.0F, 1.0F, 0.0F, 0.0F); GL11.glRotatef(45.0F, 0.0F, 1.0F, 0.0F);
        } else {
            float h = 0.375F;
            GL11.glTranslatef(0.25F, 0.1875F, -0.1875F); GL11.glScalef(h, h, h);
            GL11.glRotatef(60.0F, 0.0F, 0.0F, 1.0F); GL11.glRotatef(-90.0F, 1.0F, 0.0F, 0.0F); GL11.glRotatef(20.0F, 0.0F, 0.0F, 1.0F);
        }
        this.dispatcher.itemInHandRenderer.render(it);
        GL11.glPopMatrix();
    }
}
