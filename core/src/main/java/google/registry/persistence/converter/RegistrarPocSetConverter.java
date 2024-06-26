// Copyright 2020 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.persistence.converter;

import google.registry.model.registrar.RegistrarPocBase.Type;
import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

/** JPA {@link AttributeConverter} for storing/retrieving {@code Set<Type>}. */
@Converter(autoApply = true)
public class RegistrarPocSetConverter extends StringSetConverterBase<Type> {
  @Override
  String toString(Type element) {
    return element.name();
  }

  @Override
  Type fromString(String value) {
    return Type.valueOf(value);
  }
}
