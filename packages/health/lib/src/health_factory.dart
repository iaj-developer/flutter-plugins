part of '../health.dart';

/// Main class for the Plugin
class HealthFactory {
  static const MethodChannel _channel = MethodChannel('flutter_health');
  String? _deviceId;
  final _deviceInfo = DeviceInfoPlugin();

  static PlatformType _platformType =
      Platform.isAndroid ? PlatformType.ANDROID : PlatformType.IOS;

  /// Check if a given data type is available on the platform
  bool _isDataTypeAvailable(HealthDataType dataType) =>
      _platformType == PlatformType.ANDROID
          ? _dataTypeKeysAndroid.contains(dataType)
          : _dataTypeKeysIOS.contains(dataType);

  /// Request if has access to GoogleFit/Apple HealthKit
  Future<bool> hasAuthorization(List<HealthDataType> types) async {
    /// If BMI is requested, then also ask for weight and height
    if (types.contains(HealthDataType.BODY_MASS_INDEX)) {
      if (!types.contains(HealthDataType.WEIGHT)) {
        types.add(HealthDataType.WEIGHT);
      }

      if (!types.contains(HealthDataType.HEIGHT)) {
        types.add(HealthDataType.HEIGHT);
      }
    }

    List<String> keys = types.map((e) => _enumToString(e)).toList();
    final bool hasAuthorization =
        await _channel.invokeMethod('hasAuthorization', {'types': keys});
    return hasAuthorization;
  }

  /// Request access to GoogleFit/Apple HealthKit
  Future<bool> requestAuthorization(List<HealthDataType> types) async {
    /// If BMI is requested, then also ask for weight and height
    if (types.contains(HealthDataType.BODY_MASS_INDEX)) {
      if (!types.contains(HealthDataType.WEIGHT)) {
        types.add(HealthDataType.WEIGHT);
      }

      if (!types.contains(HealthDataType.HEIGHT)) {
        types.add(HealthDataType.HEIGHT);
      }
    }

    List<String> keys = types.map((e) => _enumToString(e)).toList();
    final bool isAuthorized =
        await _channel.invokeMethod('requestAuthorization', {'types': keys});
    return isAuthorized;
  }

  /// Calculate the BMI using the last observed height and weight values.
  Future<List<HealthDataPoint>> _computeAndroidBMI(
      DateTime startDate, DateTime endDate) async {
    List<HealthDataPoint> heights =
        await _prepareQuery(startDate, endDate, HealthDataType.HEIGHT);

    if (heights.isEmpty) {
      return [];
    }

    List<HealthDataPoint> weights =
        await _prepareQuery(startDate, endDate, HealthDataType.WEIGHT);

    double h = heights.last.value.toDouble();

    const dataType = HealthDataType.BODY_MASS_INDEX;
    final unit = _dataTypeToUnit[dataType]!;

    final bmiHealthPoints = <HealthDataPoint>[];
    for (var i = 0; i < weights.length; i++) {
      final bmiValue = weights[i].value.toDouble() / (h * h);
      final x = HealthDataPoint(bmiValue, dataType, unit, weights[i].dateFrom,
          weights[i].dateTo, _platformType, _deviceId!);

      bmiHealthPoints.add(x);
    }
    return bmiHealthPoints;
  }

  /// Get an array of [HealthDataPoint] from an array of [HealthDataType]
  Future<List<HealthDataPoint>> getHealthDataFromTypes(
      DateTime startDate, DateTime endDate, List<HealthDataType> types) async {
    final dataPoints = <HealthDataPoint>[];

    for (var type in types) {
      final result = await _prepareQuery(startDate, endDate, type);
      dataPoints.addAll(result);
    }
    return removeDuplicates(dataPoints);
  }

  /// Get an array of limit [HealthDataPoint] from an array of [HealthDataType]
  /// limit = 1 means get the latest [HealthDataPoint] of [HealthDataType]
  Future<List<HealthDataPoint>> getLimitHealthDataFromTypes(
      DateTime endDate, List<HealthDataType> types, int limit,
      {DateTime? startDate}) async {
    final dataPoints = <HealthDataPoint>[];

    startDate ??= DateTime(2000);
    for (var type in types) {
      final result =
          await _prepareQuery(startDate, endDate, type, limit: limit);
      dataPoints.addAll(result);
    }
    return removeDuplicates(dataPoints);
  }

  /// Prepares a query, i.e. checks if the types are available, etc.
  Future<List<HealthDataPoint>> _prepareQuery(
      DateTime startDate, DateTime endDate, HealthDataType dataType,
      {int limit = 0}) async {
    /// Ask for device ID only once
    _deviceId ??= _platformType == PlatformType.ANDROID
        ? (await _deviceInfo.androidInfo).androidId
        : (await _deviceInfo.iosInfo).identifierForVendor;

    /// If not implemented on platform, throw an exception
    if (!_isDataTypeAvailable(dataType)) {
      throw _HealthException(
          dataType, 'Not available on platform $_platformType');
    }

    /// If BodyMassIndex is requested on Android, calculate this manually in Dart
    if (dataType == HealthDataType.BODY_MASS_INDEX &&
        _platformType == PlatformType.ANDROID) {
      return _computeAndroidBMI(startDate, endDate);
    }
    return await _dataQuery(startDate, endDate, dataType, limit);
  }

  /// The main function for fetching health data
  Future<List<HealthDataPoint>> _dataQuery(DateTime startDate, DateTime endDate,
      HealthDataType dataType, int limit) async {
    // Set parameters for method channel request
    final args = <String, dynamic>{
      'dataTypeKey': _enumToString(dataType),
      'startDate': startDate.millisecondsSinceEpoch,
      'endDate': endDate.millisecondsSinceEpoch,
      'limit': limit
    };

    final unit = _dataTypeToUnit[dataType]!;

    final fetchedDataPoints = await _channel.invokeMethod('getData', args);
    if (fetchedDataPoints != null) {
      return fetchedDataPoints.map<HealthDataPoint>((e) {
        num value = e['value'];
        DateTime from = DateTime.fromMillisecondsSinceEpoch(e['date_from']);
        DateTime to = DateTime.fromMillisecondsSinceEpoch(e['date_to']);
        return HealthDataPoint(
            value, dataType, unit, from, to, _platformType, _deviceId!);
      }).toList();
    } else {
      return <HealthDataPoint>[];
    }
  }

  /// Given an array of [HealthDataPoint]s, this method will return the array
  /// without any duplicates.
  static List<HealthDataPoint> removeDuplicates(List<HealthDataPoint> points) {
    final unique = <HealthDataPoint>[];

    for (var p in points) {
      var seenBefore = false;
      for (var s in unique) {
        if (s == p) {
          seenBefore = true;
        }
      }
      if (!seenBefore) {
        unique.add(p);
      }
    }
    return unique;
  }
}
