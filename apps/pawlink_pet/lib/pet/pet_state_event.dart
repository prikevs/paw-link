import 'pet_action.dart';

enum PetStateSourceType { manual, replay, ble, android }

class PetStateEvent {
  const PetStateEvent({
    required this.action,
    required this.source,
    required this.timestamp,
    this.confidence,
  });

  factory PetStateEvent.now({
    required PetAction action,
    required PetStateSourceType source,
    double? confidence,
  }) {
    return PetStateEvent(
      action: action,
      source: source,
      confidence: confidence,
      timestamp: DateTime.now(),
    );
  }

  final PetAction action;
  final PetStateSourceType source;
  final double? confidence;
  final DateTime timestamp;
}
