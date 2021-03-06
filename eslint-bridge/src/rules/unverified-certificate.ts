/*
 * SonarQube JavaScript Plugin
 * Copyright (C) 2011-2020 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
// https://jira.sonarsource.com/browse/RSPEC-4830

import { Rule } from 'eslint';
import * as estree from 'estree';
import { getPropertyWithValue, getValueOfExpression, isCallToFQN, toEncodedMessage } from './utils';

export const rule: Rule.RuleModule = {
  meta: {
    schema: [
      {
        // internal parameter for rules having secondary locations
        enum: ['sonar-runtime'],
      },
    ],
  },
  create(context: Rule.RuleContext) {
    const MESSAGE = 'Enable server certificate validation on this SSL/TLS connection.';
    function checkSensitiveArgument(
      callExpression: estree.CallExpression,
      sensitiveArgumentIndex: number,
    ) {
      if (callExpression.arguments.length < sensitiveArgumentIndex + 1) {
        return;
      }
      const sensitiveArgument = callExpression.arguments[sensitiveArgumentIndex];
      const secondaryLocations: estree.Node[] = [];
      const argumentValue = getValueOfExpression(context, sensitiveArgument, 'ObjectExpression');
      if (!argumentValue) {
        return;
      }
      if (sensitiveArgument !== argumentValue) {
        secondaryLocations.push(argumentValue);
      }
      const unsafeRejectUnauthorizedConfiguration = getPropertyWithValue(
        context,
        argumentValue,
        'rejectUnauthorized',
        false,
      );
      if (unsafeRejectUnauthorizedConfiguration) {
        secondaryLocations.push(unsafeRejectUnauthorizedConfiguration);
        context.report({
          node: callExpression.callee,
          message: toEncodedMessage(MESSAGE, secondaryLocations),
        });
      }
    }

    return {
      CallExpression: (node: estree.Node) => {
        const callExpression = node as estree.CallExpression;
        if (isCallToFQN(context, callExpression, 'https', 'request')) {
          checkSensitiveArgument(callExpression, 0);
        }
        if (isCallToFQN(context, callExpression, 'request', 'get')) {
          checkSensitiveArgument(callExpression, 0);
        }
        if (isCallToFQN(context, callExpression, 'tls', 'connect')) {
          checkSensitiveArgument(callExpression, 2);
        }
      },
    };
  },
};
