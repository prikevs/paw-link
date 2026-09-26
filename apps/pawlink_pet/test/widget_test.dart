import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pawlink_pet/pet/paw_link_pet.dart';
import 'package:pawlink_pet/pet/pet_action.dart';

void main() {
  testWidgets('pet exposes its current action to accessibility', (
    tester,
  ) async {
    await tester.pumpWidget(
      const MaterialApp(
        home: Scaffold(body: PawLinkPet(action: PetAction.groom)),
      ),
    );

    expect(find.bySemanticsLabel('Paw Link 宠物，当前动作：梳毛'), findsOneWidget);
  });
}
