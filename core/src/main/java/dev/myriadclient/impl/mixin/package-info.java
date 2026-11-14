/**
 * Core's mixins: where each feature hooks Minecraft. Every mixin is named after the class it targets
 * ({@code <Target>Mixin}, {@code <Target>Accessor}), with a feature suffix when a second one targets the same class,
 * and says in its header what it serves. Mod-specific hooks live under {@code compat.<mod id>} and apply only with that mod
 * installed (CompatMixinPlugin).
 *
 * <ul>
 *   <li><b>Packets and the click budget:</b> ConnectionMixin (PacketEvent, held late actions, the packet budget),
 *       MultiPlayerGameModeMixin (clicks counted, interactions), ClientPacketListenerMixin,
 *       ClientCommonPacketListenerImplMixin, LocalPlayerMixin (movement packets), AbstractContainerMenuAccessor
 *       (MenuSync).</li>
 *   <li><b>Inventory masks, holds and swaps:</b> AbstractContainerScreenMixin, HudMixin,
 *       ArmedEntityRenderStateMixin, HumanoidMobRendererMixin, FirstPersonHandsAndItemsMixin, PlayerMixin,
 *       MinecraftMixin, MultiPlayerGameModeMixin, LocalPlayerAccessor (the sprint bracket, teleports noted as sent), BlockItemAccessor
 *       (ClickSolver).</li>
 *   <li><b>Containers and boxes:</b> AbstractContainerScreenMixin (held containers, sorts, HandClicks),
 *       ClientPacketListenerMixin (ContainerTracker), MultiPlayerGameModeMixin, CreativeSlotWrapperAccessor
 *       (ScreenSlots).</li>
 *   <li><b>Rotations, movement and the elytra:</b> LocalPlayerMixin, LivingEntityMixin, EntityMixin,
 *       ClientPacketListenerMixin (setbacks), ClientCommonPacketListenerImplMixin (held ping answers),
 *       ConnectionMixin (LeavingGlide).</li>
 *   <li><b>Rendering and highlights:</b> GameRendererMixin, LevelRendererMixin, LevelExtractorMixin,
 *       RenderPipelinesMixin, ShaderManagerMixin, EntityRendererMixin, EntityRenderStateMixin,
 *       EntityRenderDispatcherMixin, LivingEntityRendererMixin, FeatureRenderDispatcherMixin, AvatarRendererMixin,
 *       FirstPersonHandsAndItemsRendererMixin, WalkAnimationStateAccessor (previews), BlockStateBaseMixin
 *       (BlockRenderEvent), compat.iris.IrisHandRendererMixin,
 *       compat.entityculling.ClientLevelEntityCullingMixin.</li>
 *   <li><b>Camera:</b> CameraMixin, LevelExtractorMixin (drawing yourself), GameRendererMixin (the hand),
 *       EntityMixin (the view ray).</li>
 *   <li><b>Input:</b> KeyboardHandlerMixin, MouseHandlerMixin, LocalPlayerMixin (InputEvent), MinecraftMixin (your
 *       own use and attack keys).</li>
 *   <li><b>Chat and commands:</b> ChatComponentMixin, CommandSuggestionsMixin, ClientPacketListenerMixin
 *       (ChatSendEvent), ScreenMixin (commands from clicked chat text).</li>
 *   <li><b>World events:</b> ClientLevelMixin, ClientChunkCacheMixin, BlockStateBaseMixin (CollisionShapeEvent),
 *       SoundManagerMixin, ItemStackMixin, LevelMixin (the profiler's block reads).</li>
 *   <li><b>Themed screens:</b> ThemedScreenMixin, ScreenMixin, AbstractContainerScreenMixin,
 *       AbstractContainerScreenAccessor, GuiGraphicsExtractorMixin, TooltipRenderUtilMixin,
 *       AbstractSelectionListMixin, TitleScreenMixin, PauseScreenMixin, DisconnectedScreenAccessor; widgets:
 *       AbstractButtonMixin, CycleButtonMixin, EditBoxMixin, ImageButtonMixin; single screens:
 *       AbstractFurnaceScreenMixin, AbstractMountInventoryScreenMixin, AnvilScreenMixin, BeaconScreenButtonMixin,
 *       BeaconSpriteScreenButtonMixin, BrewingStandScreenMixin, CreativeModeInventoryScreenMixin,
 *       EffectsInInventoryMixin, EnchantmentScreenMixin, GrindstoneScreenMixin, InventoryScreenMixin,
 *       LoomScreenMixin, MerchantScreenMixin, SmithingScreenMixin, StonecutterScreenMixin; the recipe book:
 *       RecipeBookComponentMixin, RecipeBookTabButtonMixin, RecipeButtonMixin, OverlayRecipeComponentMixin.</li>
 *   <li><b>Theme text:</b> FontManagerMixin, FontSetAccessor, FontMixin, GuiTextRenderStateMixin,
 *       GuiGraphicsExtractorItemCountMixin.</li>
 *   <li><b>Themed HUD and the UI's frame:</b> HudMixin, ExperienceBarMixin, GuiRendererMixin (frosted windows'
 *       blur), GuiMixin (ScreenOpenEvent).</li>
 *   <li><b>Myriad's cape:</b> AbstractClientPlayerMixin, CapeLayerMixin, OptionsMixin.</li>
 * </ul>
 */
package dev.myriadclient.impl.mixin;
