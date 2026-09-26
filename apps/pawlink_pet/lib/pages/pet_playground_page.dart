import 'dart:io';

import 'package:flutter/material.dart';
import 'package:window_manager/window_manager.dart';

import '../pet/paw_link_pet.dart';
import '../pet/pet_action.dart';
import '../state/pet_controller.dart';
import '../state/pet_state_source.dart';

class PetPlaygroundPage extends StatefulWidget {
  const PetPlaygroundPage({super.key});

  @override
  State<PetPlaygroundPage> createState() => _PetPlaygroundPageState();
}

class _PetPlaygroundPageState extends State<PetPlaygroundPage> {
  late final ManualPetStateSource _manualSource;
  late final PetController _controller;

  bool get _isDesktop =>
      Platform.isMacOS || Platform.isWindows || Platform.isLinux;

  @override
  void initState() {
    super.initState();
    _manualSource = ManualPetStateSource();
    _controller = PetController()..bind(_manualSource);
  }

  @override
  void dispose() {
    _controller.dispose();
    _manualSource.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _isDesktop
          ? Colors.transparent
          : const Color(0xfff7f2e9),
      body: SafeArea(
        child: AnimatedBuilder(
          animation: _controller,
          builder: (context, child) {
            return LayoutBuilder(
              builder: (context, constraints) {
                if (_isDesktop) {
                  return _DesktopPetPanel(
                    action: _controller.action,
                    onSelected: _manualSource.select,
                  );
                }
                return _MobilePetPage(
                  action: _controller.action,
                  onSelected: _manualSource.select,
                  wide: constraints.maxWidth >= 720,
                );
              },
            );
          },
        ),
      ),
    );
  }
}

class _DesktopPetPanel extends StatelessWidget {
  const _DesktopPetPanel({required this.action, required this.onSelected});

  final PetAction action;
  final ValueChanged<PetAction> onSelected;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        GestureDetector(
          behavior: HitTestBehavior.translucent,
          onPanStart: (_) => windowManager.startDragging(),
          child: SizedBox(
            height: 316,
            width: double.infinity,
            child: PawLinkPet(action: action, size: 310),
          ),
        ),
        Container(
          margin: const EdgeInsets.symmetric(horizontal: 12),
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
          decoration: BoxDecoration(
            color: const Color(0xf2ffffff),
            borderRadius: BorderRadius.circular(22),
            boxShadow: const [
              BoxShadow(
                color: Color(0x26000000),
                blurRadius: 18,
                offset: Offset(0, 8),
              ),
            ],
          ),
          child: _ActionSelector(
            action: action,
            onSelected: onSelected,
            compact: true,
          ),
        ),
      ],
    );
  }
}

class _MobilePetPage extends StatelessWidget {
  const _MobilePetPage({
    required this.action,
    required this.onSelected,
    required this.wide,
  });

  final PetAction action;
  final ValueChanged<PetAction> onSelected;
  final bool wide;

  @override
  Widget build(BuildContext context) {
    final stage = _PetStage(action: action);
    final controls = _ControlCard(action: action, onSelected: onSelected);

    return CustomScrollView(
      slivers: [
        SliverAppBar.large(
          backgroundColor: Colors.transparent,
          title: const Text('Paw Link'),
          actions: [
            Padding(
              padding: const EdgeInsets.only(right: 16),
              child: _LiveBadge(action: action),
            ),
          ],
        ),
        SliverPadding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 28),
          sliver: SliverToBoxAdapter(
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 940),
                child: wide
                    ? Row(
                        crossAxisAlignment: CrossAxisAlignment.center,
                        children: [
                          Expanded(flex: 5, child: stage),
                          const SizedBox(width: 24),
                          Expanded(flex: 4, child: controls),
                        ],
                      )
                    : Column(
                        children: [stage, const SizedBox(height: 20), controls],
                      ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _PetStage extends StatelessWidget {
  const _PetStage({required this.action});

  final PetAction action;

  @override
  Widget build(BuildContext context) {
    return AspectRatio(
      aspectRatio: 1,
      child: Container(
        decoration: BoxDecoration(
          gradient: const LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: [Color(0xffffe4ba), Color(0xfffff8ed)],
          ),
          borderRadius: BorderRadius.circular(36),
        ),
        child: Stack(
          children: [
            Positioned(
              top: 22,
              left: 24,
              child: Text(
                action.label,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                  color: const Color(0xff6d3d29),
                  fontWeight: FontWeight.w800,
                ),
              ),
            ),
            Center(child: PawLinkPet(action: action, size: 330)),
          ],
        ),
      ),
    );
  }
}

class _ControlCard extends StatelessWidget {
  const _ControlCard({required this.action, required this.onSelected});

  final PetAction action;
  final ValueChanged<PetAction> onSelected;

  @override
  Widget build(BuildContext context) {
    return Card(
      elevation: 0,
      color: Colors.white,
      child: Padding(
        padding: const EdgeInsets.all(22),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('动作预览', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 6),
            Text(
              '先手动检查角色表现。接入项圈后，这里会由 BLE 分类结果自动驱动。',
              style: Theme.of(context).textTheme.bodyMedium
                  ?.copyWith(color: const Color(0xff716b66), height: 1.5),
            ),
            const SizedBox(height: 20),
            _ActionSelector(action: action, onSelected: onSelected),
            const SizedBox(height: 20),
            const Divider(),
            const SizedBox(height: 12),
            const Row(
              children: [
                Icon(Icons.cable_rounded, size: 20, color: Color(0xff6c6bff)),
                SizedBox(width: 9),
                Expanded(child: Text('传感器接口已预留 · 当前使用手动输入')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ActionSelector extends StatelessWidget {
  const _ActionSelector({
    required this.action,
    required this.onSelected,
    this.compact = false,
  });

  final PetAction action;
  final ValueChanged<PetAction> onSelected;
  final bool compact;

  IconData _icon(PetAction value) => switch (value) {
    PetAction.sleep => Icons.bedtime_rounded,
    PetAction.idle => Icons.pets_rounded,
    PetAction.walk => Icons.directions_walk_rounded,
    PetAction.eat => Icons.restaurant_rounded,
    PetAction.groom => Icons.auto_awesome_rounded,
    PetAction.shake => Icons.vibration_rounded,
  };

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: compact ? 4 : 8,
      runSpacing: compact ? 4 : 8,
      alignment: WrapAlignment.center,
      children: [
        for (final value in PetAction.values)
          Semantics(
            button: true,
            selected: value == action,
            label: value.label,
            child: ChoiceChip(
              avatar: Icon(_icon(value), size: compact ? 15 : 18),
              label: compact ? const SizedBox.shrink() : Text(value.label),
              selected: value == action,
              showCheckmark: false,
              tooltip: value.label,
              onSelected: (_) => onSelected(value),
              visualDensity: compact
                  ? VisualDensity.compact
                  : VisualDensity.standard,
            ),
          ),
      ],
    );
  }
}

class _LiveBadge extends StatelessWidget {
  const _LiveBadge({required this.action});

  final PetAction action;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
      decoration: BoxDecoration(
        color: const Color(0xffe9e8ff),
        borderRadius: BorderRadius.circular(99),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          const CircleAvatar(radius: 4, backgroundColor: Color(0xff6c6bff)),
          const SizedBox(width: 7),
          Text(action.semanticLabel),
        ],
      ),
    );
  }
}
