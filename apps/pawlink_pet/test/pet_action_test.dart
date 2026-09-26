import 'package:flutter_test/flutter_test.dart';
import 'package:pawlink_pet/pet/pet_action.dart';

void main() {
  test('maps deployed classifier labels to visual actions', () {
    expect(PetAction.fromClassifierLabel('rest'), PetAction.sleep);
    expect(PetAction.fromClassifierLabel('locomotion'), PetAction.walk);
    expect(PetAction.fromClassifierLabel('feed'), PetAction.eat);
    expect(PetAction.fromClassifierLabel('groom'), PetAction.groom);
    expect(PetAction.fromClassifierLabel('collar_shake'), PetAction.shake);
  });

  test('ignores unknown classifier labels', () {
    expect(PetAction.fromClassifierLabel('unknown'), isNull);
  });
}
