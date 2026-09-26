import 'dart:async';

import 'package:flutter/foundation.dart';

import '../pet/pet_action.dart';
import '../pet/pet_state_event.dart';
import 'pet_state_source.dart';

class PetController extends ChangeNotifier {
  PetController({PetAction initialAction = PetAction.idle})
    : _action = initialAction;

  PetAction _action;
  PetStateEvent? _lastEvent;
  StreamSubscription<PetStateEvent>? _subscription;

  PetAction get action => _action;
  PetStateEvent? get lastEvent => _lastEvent;

  void bind(PetStateSource source) {
    _subscription?.cancel();
    _subscription = source.events.listen(accept);
  }

  void accept(PetStateEvent event) {
    _lastEvent = event;
    if (_action == event.action) {
      notifyListeners();
      return;
    }
    _action = event.action;
    notifyListeners();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }
}
