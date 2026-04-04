package com.river_quinn.enchantment_custom_table.world.inventory;

import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;
import com.river_quinn.enchantment_custom_table.Config;
import com.river_quinn.enchantment_custom_table.block.entity.EnchantingCustomTableBlockEntity;
import com.river_quinn.enchantment_custom_table.init.ModMenus;
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
		var itemStackToPut = entity.containerMenu.getCarried();
		if (slotId >= 2 && slotId < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE && clickType != ClickType.QUICK_MOVE) {
			var itemStackToReplace = itemHandler.getStackInSlot(slotId);
			if (!itemStackToPut.isEmpty() && !itemStackToReplace.isEmpty()) {
				var enchantmentsOnNewStack = getEnchantmentInstanceFromEnchantedBook(itemStackToPut);
				var enchantmentOnOldStack = getEnchantmentInstanceFromEnchantedBook(itemStackToReplace).get(0);
				var hasDuplicateEnchantment = enchantmentsOnNewStack.stream().anyMatch(enchantment ->
						enchantment.enchantment.equals(enchantmentOnOldStack.enchantment));
				if (hasDuplicateEnchantment) {
					addEnchantment(itemStackToPut, slotId, true);
					entity.containerMenu.setCarried(ItemStack.EMPTY);
					return;
				}
			}

			int enchantmentIndexInCache = (slotId - 2) + currentPage * ENCHANTED_BOOK_SLOT_SIZE;

			if (!itemStackToReplace.isEmpty()) {
				entity.containerMenu.setCarried(itemStackToReplace.copy());
				var hasRegenerated = removeEnchantment(itemStackToReplace);
				if (!hasRegenerated) {
					enchantmentsOnCurrentTool.set(enchantmentIndexInCache, ItemStack.EMPTY);
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

		addSlot(new SlotItemHandler(itemHandler, 0, 8, 8) {
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

		addSlot(new SlotItemHandler(itemHandler, 1, 42, 8) {
			@Override
			public boolean mayPlace(ItemStack stack) {
				return Items.ENCHANTED_BOOK == stack.getItem()
						&& !getItemHandler().getStackInSlot(0).isEmpty()
						&& (Config.ignoreEnchantmentLevelLimit || checkCanPlaceEnchantedBook(stack));
			}

			@Override
			public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
				return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.fromNamespaceAndPath("enchantment_custom_table", "item/empty_slot_book"));
			}

			@Override
			public void setByPlayer(ItemStack newStack, ItemStack oldStack) {
				super.setByPlayer(newStack, oldStack);
				if (!newStack.isEmpty()) {
					addEnchantment(newStack, 1, true);
				}
			}
		});

		int enchanted_book_index = 0;
		for (int row = 0; row < ENCHANTED_BOOK_SLOT_ROW_COUNT; row++) {
			int yPos = 8 + row * 18;
			for (int col = 0; col < ENCHANTED_BOOK_SLOT_COLUMN_COUNT; col++) {
				int xPos = 61 + col * 18;
				addSlot(new SlotItemHandler(itemHandler, enchanted_book_index + 2, xPos, yPos) {
						@Override
						public boolean mayPlace(ItemStack stack) {
							return Items.ENCHANTED_BOOK == stack.getItem()
									&& !getItemHandler().getStackInSlot(0).isEmpty()
									&& (Config.ignoreEnchantmentLevelLimit || checkCanPlaceEnchantedBook(stack));
						}

						@Override
						public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
							return Pair.of(InventoryMenu.BLOCK_ATLAS, ResourceLocation.fromNamespaceAndPath("enchantment_custom_table", "item/empty_slot_book"));
						}
					});
				enchanted_book_index++;
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
		ItemStack itemstack = ItemStack.EMPTY;
		Slot slot = (Slot) this.slots.get(index);
		ItemStack itemStackToOperate = slot.getItem().copy();
		if (slot != null && slot.hasItem()) {
			ItemStack itemstack1 = slot.getItem();
			itemstack = itemstack1.copy();
			if (index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE) {
				if (!this.moveItemStackTo(itemstack1, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, this.slots.size(), true))
					return ItemStack.EMPTY;
			} else if (!this.moveItemStackTo(itemstack1, 0, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, false)) {
				if (index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE + 27) {
					if (!this.moveItemStackTo(itemstack1, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE + 27, this.slots.size(), true))
						return ItemStack.EMPTY;
				} else {
					if (!this.moveItemStackTo(itemstack1, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE, ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE + 27, false))
						return ItemStack.EMPTY;
				}
				return ItemStack.EMPTY;
			}
			if (itemstack1.isEmpty())
				slot.set(ItemStack.EMPTY);
			else
				slot.setChanged();
			if (itemstack1.getCount() == itemstack.getCount())
				return ItemStack.EMPTY;
			slot.onTake(playerIn, itemstack1);
		}

		if (index > 1 && index < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE) {
			removeEnchantment(itemStackToOperate);
		}
		return itemstack;
	}

	@Override
	protected boolean moveItemStackTo(ItemStack p_38904_, int p_38905_, int p_38906_, boolean p_38907_) {
		boolean flag = false;
		int i = p_38905_;
		if (p_38907_) {
			i = p_38906_ - 1;
		}
		if (p_38904_.isStackable()) {
			while (!p_38904_.isEmpty() && (p_38907_ ? i >= p_38905_ : i < p_38906_)) {
				Slot slot = this.slots.get(i);
				ItemStack itemstack = slot.getItem();
				if (slot.mayPlace(itemstack) && !itemstack.isEmpty() && ItemStack.isSameItemSameComponents(p_38904_, itemstack)) {
					int j = itemstack.getCount() + p_38904_.getCount();
					int k = slot.getMaxStackSize(itemstack);
					if (j <= k) {
						p_38904_.setCount(0);
						itemstack.setCount(j);
						slot.set(itemstack);
						flag = true;
					} else if (itemstack.getCount() < k) {
						p_38904_.shrink(k - itemstack.getCount());
						itemstack.setCount(k);
						slot.set(itemstack);
						flag = true;
					}
				}
				if (p_38907_) {
					i--;
				} else {
					i++;
				}
			}
		}
		if (!p_38904_.isEmpty()) {
			if (p_38907_) {
				i = p_38906_ - 1;
			} else {
				i = p_38905_;
			}
			while (p_38907_ ? i >= p_38905_ : i < p_38906_) {
				Slot slot1 = this.slots.get(i);
				ItemStack itemstack1 = slot1.getItem();
				if (itemstack1.isEmpty() && slot1.mayPlace(p_38904_)) {
					int l = slot1.getMaxStackSize(p_38904_);
					slot1.setByPlayer(p_38904_.split(Math.min(p_38904_.getCount(), l)));
					slot1.setChanged();
					flag = true;
					break;
				}
				if (p_38907_) {
					i--;
				} else {
					i++;
				}
			}
		}
		return flag;
	}

	@Override
	public void removed(@NotNull Player playerIn) {
		super.removed(playerIn);
		if (playerIn instanceof ServerPlayer) {
			playerIn.getInventory().placeItemBackInInventory(itemHandler.getStackInSlot(0));
		}
	}

	public List<EnchantmentInstance> getEnchantmentInstanceFromEnchantedBook(ItemStack enchantedBookItemStack) {
		DataComponentType<ItemEnchantments> componentType = EnchantmentHelper.getComponentType(enchantedBookItemStack);
		var componentMap = enchantedBookItemStack.getComponents().get(componentType);
		List<EnchantmentInstance> enchantmentOfBook = new ArrayList<>();
		if (componentMap != null) {
			for (Object2IntMap.Entry<Holder<Enchantment>> entry : componentMap.entrySet()) {
				enchantmentOfBook.add(new EnchantmentInstance(entry.getKey(), entry.getIntValue()));
			}
		}
		return enchantmentOfBook;
	}

	public boolean checkCanPlaceEnchantedBook(ItemStack stack) {
		if (Config.ignoreEnchantmentLevelLimit) return true;
		var itemEnchantments = stack.get(EnchantmentHelper.getComponentType(stack));
		var itemToEnchant = itemHandler.getStackInSlot(0);
		var itemEnchantmentsOnTool = itemToEnchant.get(EnchantmentHelper.getComponentType(itemToEnchant));
		if (itemEnchantments == null || itemEnchantmentsOnTool == null) {
			return true;
		}
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : itemEnchantments.entrySet()) {
			int add = entry.getIntValue();
			int now = itemEnchantmentsOnTool.getLevel(entry.getKey());
			int max = entry.getKey().value().getMaxLevel();
			if (now + add > max) {
				return false;
			}
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

	public void nextPage() {
		if (currentPage < totalPage - 1) {
			turnPage(currentPage + 1);
		}
	}

	public void previousPage() {
		if (currentPage > 0) {
			turnPage(currentPage - 1);
		}
	}

	public void turnPage(int targetPage) {
		if (targetPage < 0 || targetPage >= totalPage) return;
		int indexOffset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;
		for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
			int listIdx = i + indexOffset;
			int slotIdx = i + 2;
			if (listIdx < enchantmentsOnCurrentTool.size())
				enchantmentsOnCurrentTool.set(listIdx, itemHandler.getStackInSlot(slotIdx));
		}
		currentPage = targetPage;
		updateEnchantedBookSlots();
	}

	public void updateEnchantedBookSlots() {
		itemHandler.setStackInSlot(1, ItemStack.EMPTY);
		int offset = currentPage * ENCHANTED_BOOK_SLOT_SIZE;
		if (totalPage > 0) {
			for (int i = 0; i < ENCHANTED_BOOK_SLOT_SIZE; i++) {
				int listIdx = i + offset;
				int slotIdx = i + 2;
				itemHandler.setStackInSlot(slotIdx, enchantmentsOnCurrentTool.get(listIdx));
			}
		}
	}

	public void clearCache() {
		for (int i = 2; i < ENCHANTMENT_CUSTOM_TABLE_SLOT_SIZE; i++) {
			itemHandler.setStackInSlot(i, ItemStack.EMPTY);
		}
		genEnchantedBookCache();
	}

	public void clearPage() {
		currentPage = 0;
		totalPage = 0;
		enchantmentsOnCurrentTool.clear();
	}

	public void genEnchantedBookCache() {
		ItemStack tool = itemHandler.getStackInSlot(0);
		enchantmentsOnCurrentTool.clear();
		int pages = 1;

		if (!tool.isEmpty()) {
			ItemEnchantments ench = tool.get(EnchantmentHelper.getComponentType(tool));
			if (ench != null && !ench.isEmpty()) {
				if (tool.is(Items.ENCHANTED_BOOK) && ench.size() == 1) {
					var entry = ench.entrySet().iterator().next();
					int lvl = entry.getIntValue();
					if (lvl > 1) {
						List<Integer> split = new ArrayList<>();
						while (lvl > 0) {
							if (lvl == 2) {
								split.add(1);
								lvl = 0;
							} else {
								int add = (lvl + 1) / 2;
								split.add(add);
								lvl -= add;
							}
						}
						for (int lv : split) {
							ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
							book.enchant(entry.getKey(), lv);
							enchantmentsOnCurrentTool.add(book);
						}
					}
				} else {
					for (var entry : ench.entrySet()) {
						ItemStack book = new ItemStack(Items.ENCHANTED_BOOK);
						book.enchant(entry.getKey(), entry.getIntValue());
						enchantmentsOnCurrentTool.add(book);
					}
				}
			}
		} else {
			pages = 0;
		}

		if (!enchantmentsOnCurrentTool.isEmpty()) {
			pages = Math.max(1, (enchantmentsOnCurrentTool.size() + ENCHANTED_BOOK_SLOT_SIZE - 1) / ENCHANTED_BOOK_SLOT_SIZE);
		}
		int fullSize = pages * ENCHANTED_BOOK_SLOT_SIZE;
		while (enchantmentsOnCurrentTool.size() < fullSize) {
			enchantmentsOnCurrentTool.add(ItemStack.EMPTY);
		}
		totalPage = pages;
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
				total = Math.min(total, e.value().getMaxLevel());
			}
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
		boolean regenerate = false;

		for (var inst : list) {
			Holder<Enchantment> e = inst.enchantment;
			int curr = mutable.getLevel(e);
			int next = Math.max(0, curr - inst.level);
			mutable.set(e, next);
		}

		tool.set(EnchantmentHelper.getComponentType(tool), mutable.toImmutable());
		int oldPages = totalPage;
		genEnchantedBookCache();
		if (totalPage != oldPages) regenerate = true;
		updateEnchantedBookSlots();
		playSound();
		return regenerate;
	}

	private void playSound() {
		if (boundBlockEntity != null) {
			world.playSound(null, boundBlockEntity.getBlockPos(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
		}
	}

	public void initMenu() {
		clearPage();
		clearCache();
	}
}
