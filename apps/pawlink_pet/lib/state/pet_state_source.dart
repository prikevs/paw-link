import 'dart:async';

import '../pet/pet_action.dart';
import '../pet/pet_state_event.dart';

/// BLE、回放和 Android 桥接层只需实现这一条事件流。
abstract interface class PetStateSource {
  Stream<PetStateEvent> get events;
}

class ManualPetStateSource implements PetStateSource {
  final _controller = StreamController<PetStateEvent>.broadcast(sync: true);

  @override
  Stream<PetStateEvent> get events => _controller.stream;

  void select(PetAction action) {
    _controller.add(
      PetStateEvent.now(action: action, source: PetStateSourceType.manual),
    );
  }

  void addClassifierResult(
    String label, {
    double? confidence,
    PetStateSourceType source = PetStateSourceType.ble,
  }) {
    final action = PetAction.fromClassifierLabel(label);
    if (action == null) return;
    _controller.add(
      PetStateEvent.now(action: action, source: source, confidence: confidence),
    );
  }

  Future<void> dispose() => _controller.close();
}
