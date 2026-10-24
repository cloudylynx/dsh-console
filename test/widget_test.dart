import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'package:dsh_console/settings.dart';

void main() {
  test('AppSettings 默认值正确', () async {
    SharedPreferences.setMockInitialValues({});
    final s = await AppSettings.load();
    expect(s.host, '');
    expect(s.port, 18800);
    expect(s.dockHeight, 64);
    expect(s.iconScale, 0.45);
  });

  test('AppSettings 持久化', () async {
    SharedPreferences.setMockInitialValues({});
    final s = await AppSettings.load();
    s.host = '192.168.1.1';
    s.dockHeight = 80;

    final s2 = await AppSettings.load();
    expect(s2.host, '192.168.1.1');
    expect(s2.dockHeight, 80);
  });
}
