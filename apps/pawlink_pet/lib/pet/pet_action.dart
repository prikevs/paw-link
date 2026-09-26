enum PetAction {
  sleep,
  idle,
  walk,
  eat,
  groom,
  shake;

  String get label => switch (this) {
    PetAction.sleep => '睡觉',
    PetAction.idle => '发呆',
    PetAction.walk => '散步',
    PetAction.eat => '吃饭',
    PetAction.groom => '梳毛',
    PetAction.shake => '抖动',
  };

  String get semanticLabel => switch (this) {
    PetAction.sleep => 'sleep',
    PetAction.idle => 'idle',
    PetAction.walk => 'walk',
    PetAction.eat => 'eat',
    PetAction.groom => 'groom',
    PetAction.shake => 'shake',
  };

  static PetAction? fromClassifierLabel(String label) {
    return switch (label.trim().toLowerCase()) {
      'rest' || 'still' || 'sleep' => PetAction.sleep,
      'locomotion' || 'walking' || 'walk' || 'circle' => PetAction.walk,
      'feed' || 'feeding' || 'eat' => PetAction.eat,
      'groom' || 'grooming' => PetAction.groom,
      'collar_shake' || 'shake_lr' || 'shake' => PetAction.shake,
      'idle' => PetAction.idle,
      _ => null,
    };
  }
}
