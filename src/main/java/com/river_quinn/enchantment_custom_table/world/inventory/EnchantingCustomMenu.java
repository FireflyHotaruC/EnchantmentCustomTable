package com.river_quinn.enchantment_custom_table.world.inventory;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.river_quinn.enchantment_custom_table.Config;
import com.river_quinn.enchantment_custom_table.block.entity.EnchantingCustomTableBlockEntity;
import com.river_quinn.enchantment_custom_table.init.ModMenus;
import com.river_quinn.enchantment_custom_table.utils.EnchantmentUtils;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.items.ItemStackHandler;
import net.neoforged.neoforge.items.SlotItemHandler;

import net.minecraft.world.level.Level;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.util.*;

public class EnchantingCustomMenu extends AbstractContainerMenu {
	public static final int ENCHANTED_BOOK_SLOT_ROW_COUNT = 4;
	public static final int ENCHANTED_BOOK_SLOT_COLUMN_COUNT = 6;
	public static final int ENCHANTED_BOOK_SLOT_SIZE = ENCHANTED_BOOK_SLOT_ROW_COUNT * ENCHANTED_BOOK_SLOT_COLUMN_COUNT;
	public static final int ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE = ENCHANTED_BOOK_SLOT_SIZE + 2;

	private final ItemStackHandler itemHandler = new ItemStackHandler(ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE){
		@Override
		public int getStackLimit(int slot, ItemStack stack) {
			return 1;
		}
	};

	private static final Logger LOGGER = LogUtils.getLogger();
	public final static HashMap<String, Object> guistate = new HashMap<>();
	public final Level world;
	public final Player entity;
	public int x, y, z;
	private ContainerLevelAccess access = ContainerLevelAccess.NULL;
	private final Map<Integer, Slot> enchantedBookSlots = new HashMap<>();
	public EnchantingCustomTableBlockEntity boundBlockEntity = null;

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		if (slotId >= 2 && slotId < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE && clickType != ClickType.QUICK_MOVE) {
			ItemStack itemStackToPut = entity.containerMenu.getCarried();
			ItemStack itemStackToReplace = itemHandler.getStackInSlot(slotId);

			if (!itemStackToPut.isEmpty() && !itemStackToReplace.isEmpty()) {
				List<EnchantmentInstance> newEnch = getEnchantmentInstanceFromEnchantedBook(itemStackToPut);
				List<EnchantmentInstance> oldEnch = getEnchantmentInstanceFromEnchantedBook(itemStackToReplace);

				if (oldEnch.isEmpty() || newEnch.isEmpty()) {
					entity.containerMenu.setCarried(itemStackToReplace.copy());
					itemHandler.setStackInSlot(slotId, itemStackToPut);
					updateEnchantedBookSlots();
					return;
				}

				EnchantmentInstance oldInst = oldEnch.get(0);
				boolean duplicate = newEnch.stream().anyMatch(e -> e.enchantment.equals(oldInst.enchantment));
				if (duplicate) {
					addEnchantment(itemStackToPut, slotId, true);
					entity.containerMenu.setCarried(ItemStack.EMPTY);
					return;
				}
			}

			int cacheIndex = (slotId - 2) + currentPage * ENCHANTED_BOOK_SLOT_SIZE;

			if (!itemStackToReplace.isEmpty()) {
				entity.containerMenu.setCarried(itemStackToReplace.copy());
				boolean regen = removeEnchantment(itemStackToReplace);
				if (!regen && cacheIndex >= 0 && cacheIndex < enchantmentsOnCurrentTool.size()) {
					enchantmentsOnCurrentTool.set(cacheIndex, ItemStack.EMPTY);
				}
			} else {
				entity.containerMenu.setCarried(ItemStack.EMPTY);
			}

			if (!itemStackToPut.isEmpty()) {
				addEnchantment(itemStackToPut, slotId);
			}

			updateEnchantedBookSlots();
		} else {
			super.clicked(slotId, button, clickType, player);
		}
	}

	public EnchantingCustomMenu(int id, Inventory inv, FriendlyByteBuf extraData) {
		super(ModMenus.ENCHANTING_CUSTOM.get(), id);
		this.entity = inv.player;
		this.world = inv.player.level();

		BlockPos pos = null;
		if (extraData != null) {
			pos = extraData.readBlockPos();
			this.x = pos.getX();
			this.y = pos.getY();
			this.z = pos.getZ();
			access = ContainerLevelAccess.create(world, pos);
		}
		if (pos != null) {
			boundBlockEntity = (EnchantingCustomTableBlockEntity) world.getBlockEntity(pos);
		}

		this.addSlot(new SlotItemHandler(itemHandler, 0, 8, 8) {
			@Override
			public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
				super.setByPlayer(newStack, oldStack);
				if (!newStack.isEmpty()) {
					genEnchantedBookCache();
					currentPage = 0;
					updateEnchantedBookSlots();
				} else {
					clearCache();
					clearPage();
				}
			}
		});

		this.addSlot(new SlotItemHandler(itemHandler, 1, 42, 8) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return stack.is(Items.ENCHANTED_BOOK)
						&& !itemHandler.getStackInSlot(0).isEmpty()
						&& (Config.ignoreEnchantmentLevelLimit || checkCanPlaceEnchantedBook(stack));
			}

			@Override
			public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
				return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.tryParse("enchantment_custom_table:item/empty_slot_book"));
			}

			@Override
			public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
				super.setByPlayer(newStack, oldStack);
				if (!newStack.isEmpty()) {
					addEnchantment(newStack, 1, true);
				}
			}
		});

		int idx = 0;
		for (int row = 0; row < 4; row++) {
			int yPos = 8 + row * 18;
			for (int col = 0; col < 6; col++) {
				int xPos = 61 + col * 18;
				int slotNum = idx + 2;
				this.enchantedBookSlots.put(idx, this.addSlot(new SlotItemHandler(itemHandler, slotNum, xPos, yPos) {
					@Override
					public boolean mayPlace(ItemStack stack) {
						return false;
					}

					@Override
					public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
						return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.tryParse("enchantment_custom_table:item/empty_slot_book"));
					}
				}));
				idx++;
			}
		}

		for (int si = 0; si < 3; ++si)
			for (int sj = 0; sj < 9; ++sj)
				addSlot(new Slot(inv, sj + (si + 1) * 9, 8 + sj * 18, 84 + si * 18));
		for (int si = 0; si < 9; ++si)
			addSlot(new Slot(inv, si, 8 + si * 18, 142));

		initMenu();
	}

	@Override
	public boolean stillValid(Player player) {
		return true;
	}

	@Override
	public ItemStack quickMoveStack(Player playerIn, int index) {
		ItemStack stack = ItemStack.EMPTY;
		Slot slot = slots.get(index);
		if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

		ItemStack slotStack = slot.getItem();
		stack = slotStack.copy();

		if (index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE) {
			if (index >= 2) return ItemStack.EMPTY;
			if (!moveItemStackTo(slotStack, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, slots.size(), true)) {
				return ItemStack.EMPTY;
			}
		} else {
			if (!moveItemStackTo(slotStack, 0, 1, false)) {
				if (!moveItemStackTo(slotStack, 1, 2, false)) {
					return ItemStack.EMPTY;
				}
			}
		}

		if (slotStack.isEmpty()) slot.set(ItemStack.EMPTY);
		else slot.setChanged();

		slot.onTake(playerIn, slotStack);
		return stack;
	}

	@Override
	public void removed(@NotNull Player playerIn) {
		super.removed(playerIn);
		if (playerIn instanceof ServerPlayer) {
			playerIn.getInventory().placeItemBackInInventory(itemHandler.getStackInSlot(0));
		}
	}

	public List<EnchantmentInstance> getEnchantmentInstanceFromEnchantedBook(ItemStack stack) {
		if (stack.isEmpty() || !stack.is(Items.ENCHANTED_BOOK)) return Collections.emptyList();
		DataComponentType<ItemEnchantments> type = EnchantmentHelper.getComponentType(stack);
		ItemEnchantments ench = stack.get(type);
		if (ench == null || ench.isEmpty()) return Collections.emptyList();

		List<EnchantmentInstance> list = new ArrayList<>();
		for (var entry : ench.entrySet()) {
			list.add(new EnchantmentInstance(entry.getKey(), entry.getIntValue()));
		}
		return list;
	}

	public boolean checkCanPlaceEnchantedBook(ItemStack stack) {
		ItemEnchantments bookEnch = stack.get(EnchantmentHelper.getComponentType(stack));
		ItemStack tool = itemHandler.getStackInSlot(0);
		ItemEnchantments toolEnch = tool.get(EnchantmentHelper.getComponentType(tool));

		if (bookEnch == null || bookEnch.isEmpty()) return true;
		if (toolEnch == null || toolEnch.isEmpty()) return true;

		for (var entry : bookEnch.entrySet()) {
			Holder<Enchantment> e = entry.getKey();
			int add = entry.getIntValue();
			int now = toolEnch.getLevel(e);
			int max = e.value().getMaxLevel();
			if (now + add > max) return false;
		}
		return true;
	}

	public int currentPage = 0;
	public int totalPage = 0;
	private final List<ItemStack> enchantmentsOnCurrentTool = new ArrayList<>();

	public void exportAllEnchantments() {
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty() || tool.is(Items.ENCHANTED_BOOK)) return;

		ItemEnchantments ench = tool.get(EnchantmentHelper.getComponentType(tool));
		if (ench == null || ench.isEmpty()) return;

		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ench);
		ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);

		for (var entry : ench.entrySet()) {
			Holder<Enchantment> e = entry.getKey();
			int lvl = entry.getIntValue();
			book.enchant(e, lvl);
			mutable.set(e, 0);
		}
		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		entity.getInventory().placeItemBackInInventory(book);
		clearCache();
		clearPage();
		playSound();
	}

	public void resetPage() { currentPage = 0; }
	public void nextPage() { if (currentPage < totalPage - 1) turnPage(currentPage + 1); }
	public void previousPage() { if (currentPage > 0) turnPage(currentPage - 1); }

	public void turnPage(int target) {
		if (target < 0 || target >= totalPage) return;
		int offset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;

		for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
			int listIdx = offset + i;
			if (listIdx >= enchantmentsOnCurrentTool.size()) break;
			enchantmentsOnCurrentTool.set(listIdx, itemHandler.getStackInSlot(2 + i));
		}

		currentPage = target;
		updateEnchantedBookSlots();
	}

	public void updateEnchantedBookSlots() {
		int offset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;
		itemHandler.setStackInSlot(1, ItemStack.EMPTY);

		for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
			int listIdx = offset + i;
			int slot = 2 + i;
			if (listIdx < enchantmentsOnCurrentTool.size()) {
				itemHandler.setStackInSlot(slot, enchantmentsOnCurrentTool.get(listIdx));
			} else {
				itemHandler.setStackInSlot(slot, ItemStack.EMPTY);
			}
		}
		broadcastFullState();
	}

	public void clearCache() {
		for (int i = 2; i < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE; i++) {
			itemHandler.setStackInSlot(i, ItemStack.EMPTY);
		}
		genEnchantedBookCache();
		broadcastFullState();
	}

	public void clearPage() {
		currentPage = 0;
		totalPage = 0;
		enchantmentsOnCurrentTool.clear();
		setChanged();
	}

	public void genEnchantedBookCache() {
		ItemStack tool = itemHandler.getStackInSlot(0);
		enchantmentsOnCurrentTool.clear();

		if (tool.isEmpty()) {
			totalPage = 0;
			return;
		}

		ItemEnchantments ench = tool.get(EnchantmentHelper.getComponentType(tool));
		if (ench == null || ench.isEmpty()) {
			totalPage = 0;
			return;
		}

		if (tool.is(Items.ENCHANTED_BOOK) && ench.entrySet().size() == 1) {
			var entry = ench.entrySet().iterator().next();
			int lvl = entry.getIntValue();
			if (lvl > 1) {
				List<Integer> split = new ArrayList<>();
				while (lvl > 0) {
					int take = Math.min(lvl, 1);
					split.add(take);
					lvl -= take;
				}
				for (int lv : split) {
					ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
					b.enchant(entry.getKey(), lv);
					enchantmentsOnCurrentTool.add(b);
				}
			}
		} else {
			for (var entry : ench.entrySet()) {
				ItemStack b = new ItemStack(Items.ENCHANTED_BOOK);
				b.enchant(entry.getKey(), entry.getIntValue());
				enchantmentsOnCurrentTool.add(b);
			}
		}

		totalPage = Math.max(1, (int) Math.ceil(enchantmentsOnCurrentTool.size() / (double) ENCHANTED_BOOK_SLOT_SIZE));
		int fullSize = totalPage * ENCHANTED_BOOK_SLOT_SIZE;
		while (enchantmentsOnCurrentTool.size() < fullSize) {
			enchantmentsOnCurrentTool.add(ItemStack.EMPTY);
		}
	}

	public IdMap<Holder<Enchantment>> getAllRegisteredEnchantments() {
		return world.registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
	}

	public void addEnchantment(ItemStack stack, int slot) {
		addEnchantment(stack, slot, false);
	}

	public void addEnchantment(ItemStack stack, int slot, boolean force) {
		List<EnchantmentInstance> list = getEnchantmentInstanceFromEnchantedBook(stack);
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty() || list.isEmpty()) return;

		ItemEnchantments toolEnch = tool.get(EnchantmentHelper.getComponentType(tool));
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(toolEnch == null ? ItemEnchantments.EMPTY : toolEnch);

		for (var inst : list) {
			Holder<Enchantment> e = inst.enchantment;
			int now = mutable.getLevel(e);
			int add = inst.level;
			int total = now + add;
			if (!Config.ignoreEnchantmentLevelLimit) {
				int max = e.value().getMaxLevel();
				total = Math.min(total, max); }
			mutable.set(e, total);
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		genEnchantedBookCache();
		updateEnchantedBookSlots();
		playSound();

		if (slot == 1) itemHandler.setStackInSlot(1, ItemStack.EMPTY);
	}

	public boolean removeEnchantment(ItemStack stack) {
		List<EnchantmentInstance> list = getEnchantmentInstanceFromEnchantedBook(stack);
		ItemStack tool = itemHandler.getStackInSlot(0);
		if (tool.isEmpty() || list.isEmpty()) return false;

		ItemEnchantments existing = tool.get(EnchantmentHelper.getComponentType(tool));
		if (existing == null || existing.isEmpty()) return false;

		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(existing);

		for (var inst : list) {
			Holder<Enchantment> e = inst.enchantment;
			int remove = inst.level;
			int curr = mutable.getLevel(e);
			int next = Math.max(0, curr - remove);
			mutable.set(e, next);
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		genEnchantedBookCache();
		updateEnchantedBookSlots();
		playSound();
		return true;
	}

	private void playSound() {
		if (boundBlockEntity != null) {
			world.playSound(
					null,
					boundBlockEntity.getBlockPos(),
					SoundEvents.ENCHANTMENT_TABLE_USE,
					SoundSource.BLOCKS,
					1.0F, 1.0F
			);
		}
	}

	public void initMenu() {
		clearPage();
		clearCache();
	}
}
