// GENERATED CODE - DO NOT MODIFY BY HAND

part of 'conflux.dart';

// **************************************************************************
// JsonSerializableGenerator
// **************************************************************************

_Conflux _$ConfluxFromJson(Map<String, dynamic> json) => _Conflux(
  id: json['id'] as String,
  created_at: DateTime.parse(json['created_at'] as String),
  user_id: json['user_id'] as String,
  plane_id: json['plane_id'] as String,
  name: json['name'] as String,
  tag: json['tag'] as String?,
  portal: json['portal'] as bool,
  last_seen: DateTime.parse(json['last_seen'] as String),
);

Map<String, dynamic> _$ConfluxToJson(_Conflux instance) => <String, dynamic>{
  'id': instance.id,
  'created_at': instance.created_at.toIso8601String(),
  'user_id': instance.user_id,
  'plane_id': instance.plane_id,
  'name': instance.name,
  'tag': instance.tag,
  'portal': instance.portal,
  'last_seen': instance.last_seen.toIso8601String(),
};
